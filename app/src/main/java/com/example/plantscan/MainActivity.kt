package com.example.plantscan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.plantscan.ml.PlantClassifier
import com.example.plantscan.ml.PredictionResult
import com.example.plantscan.ui.theme.PlantScanTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

// ────────────────────────────────────────────────────────────────────────────────
// COLORS  (same palette as before)
// ────────────────────────────────────────────────────────────────────────────────
val BgColor      = Color(0xFF0F261C)
val SurfaceColor = Color(0xFF133126)
val Surface2     = Color(0xFF173A2D)
val BorderColor  = Color(0xFF1F4A39)
val GreenColor   = Color(0xFF1F6B4F)
val GreenDim     = Color(0xFF145A3F)
val TextColor    = Color(0xFFE6F2EC)
val TextMuted    = Color(0xFF9BB8A9)
val DangerColor  = Color(0xFF74070E)
val WarningColor = Color(0xFF9E1B1F)

// ────────────────────────────────────────────────────────────────────────────────
// NAVIGATION
// ────────────────────────────────────────────────────────────────────────────────
enum class Screen { SCAN, HISTORY, ENCYCLOPEDIA }

// ────────────────────────────────────────────────────────────────────────────────
// MAIN ACTIVITY
// ────────────────────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Log.e("PlantScan", "Izin kamera ditolak")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
        setContent {
            PlantScanTheme { PlantScanApp() }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// ROOT COMPOSABLE
// ────────────────────────────────────────────────────────────────────────────────
@Composable
fun PlantScanApp() {
    val context        = LocalContext.current
    val scope          = rememberCoroutineScope()
    val classifier     = remember { PlantClassifier(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // ── Navigation ──────────────────────────────────────────────────────────────
    var currentScreen by remember { mutableStateOf(Screen.SCAN) }

    // ── Scan state ──────────────────────────────────────────────────────────────
    var results         by remember { mutableStateOf<List<PredictionResult>?>(null) }
    var capturedBitmap  by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading       by remember { mutableStateOf(false) }
    var statusMsg       by remember { mutableStateOf("Arahkan kamera ke daun tanaman") }
    var showResult      by remember { mutableStateOf(false) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    // ── History state ────────────────────────────────────────────────────────────
    var historyRecords by remember { mutableStateOf(HistoryManager.getRecords(context)) }

    DisposableEffect(Unit) {
        onDispose {
            classifier.close()
            cameraExecutor.shutdown()
        }
    }

    Scaffold(
        containerColor = BgColor,
        bottomBar = {
            PlantBottomNav(current = currentScreen, onNavigate = { currentScreen = it })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentScreen) {
                // ── SCAN SCREEN ────────────────────────────────────────────────
                Screen.SCAN -> {
                    Header(title = "PlantScan", subtitle = "Deteksi Penyakit Tanaman")
                    if (!showResult) {
                        CameraSection(
                            isLoading           = isLoading,
                            statusMsg           = statusMsg,
                            onImageCaptureReady = { imageCapture = it },
                            onScanClick         = {
                                if (!isLoading) {
                                    imageCapture?.let { cap ->
                                        isLoading = true
                                        statusMsg = "analisis gambar..."
                                        cap.takePicture(
                                            ContextCompat.getMainExecutor(context),
                                            object : ImageCapture.OnImageCapturedCallback() {
                                                override fun onCaptureSuccess(image: ImageProxy) {
                                                    scope.launch {
                                                        val bmp = imageProxyToBitmap(image)
                                                        image.close()
                                                        val output = withContext(Dispatchers.Default) {
                                                            classifier.classify(bmp)
                                                        }
                                                        capturedBitmap = bmp
                                                        results        = output
                                                        showResult     = true
                                                        isLoading      = false
                                                        statusMsg      = "Scan selesai"
                                                        // Auto-save to history
                                                        if (output.isNotEmpty()) {
                                                            withContext(Dispatchers.IO) {
                                                                HistoryManager.saveRecord(context, bmp, output[0])
                                                            }
                                                            historyRecords = HistoryManager.getRecords(context)
                                                            Toast.makeText(context, "Hasil scan tersimpan di History", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                                override fun onError(e: ImageCaptureException) {
                                                    isLoading = false
                                                    statusMsg = "Gagal: ${e.message}"
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    } else {
                        ResultSection(
                            results        = results ?: emptyList(),
                            capturedBitmap = capturedBitmap,
                            onRetry        = {
                                showResult     = false
                                results        = null
                                capturedBitmap = null
                                statusMsg      = "Arahkan kamera ke daun tanaman"
                            }
                        )
                    }
                }

                // ── HISTORY SCREEN ─────────────────────────────────────────────
                Screen.HISTORY -> {
                    HistoryScreen(
                        records  = historyRecords,
                        onDelete = { id ->
                            HistoryManager.deleteRecord(context, id)
                            historyRecords = HistoryManager.getRecords(context)
                        }
                    )
                }

                // ── ENCYCLOPEDIA SCREEN ────────────────────────────────────────
                Screen.ENCYCLOPEDIA -> {
                    EncyclopediaScreen()
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// BOTTOM NAV
// ────────────────────────────────────────────────────────────────────────────────
@Composable
fun PlantBottomNav(current: Screen, onNavigate: (Screen) -> Unit) {
    NavigationBar(
        containerColor = SurfaceColor,
        tonalElevation = 0.dp
    ) {
        val items = listOf(
            Triple(Screen.SCAN,          "📷", "Scan"),
            Triple(Screen.HISTORY,       "🕐", "History"),
            Triple(Screen.ENCYCLOPEDIA,  "📚", "Ensiklopedia")
        )
        items.forEach { (screen, emoji, label) ->
            NavigationBarItem(
                selected = current == screen,
                onClick  = { onNavigate(screen) },
                icon     = { Text(emoji, fontSize = 20.sp) },
                label    = {
                    Text(
                        label,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor      = GreenColor,
                    selectedTextColor      = GreenColor,
                    unselectedIconColor    = TextMuted,
                    unselectedTextColor    = TextMuted,
                    indicatorColor         = GreenColor.copy(alpha = 0.2f)
                )
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// SHARED HEADER
// ────────────────────────────────────────────────────────────────────────────────
@Composable
fun Header(title: String, subtitle: String) {
    Surface(color = SurfaceColor) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(GreenColor))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title,    color = GreenColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = TextMuted,  fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// CAMERA SECTION  (unchanged logic)
// ────────────────────────────────────────────────────────────────────────────────
@Composable
fun CameraSection(
    isLoading          : Boolean,
    statusMsg          : String,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onScanClick        : () -> Unit,
) {
    Column(
        modifier            = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    val previewView          = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview        = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        onImageCaptureReady(capture)
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                ctx as androidx.lifecycle.LifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, capture
                            )
                        } catch (e: Exception) {
                            Log.e("PlantScan", "Kamera error: ${e.message}")
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
            CornerBrackets()
            if (isLoading) ScanningLine()
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape)
                .background(if (isLoading) WarningColor else GreenColor))
            Spacer(Modifier.width(8.dp))
            Text(statusMsg, color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick  = onScanClick,
            enabled  = !isLoading,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape    = RoundedCornerShape(12.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = GreenColor, contentColor = BgColor)
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = BgColor, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("analisis...", fontWeight = FontWeight.Bold)
            } else {
                Text("SCAN TANAMAN", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// RESULT SECTION  (+ share button)
// ────────────────────────────────────────────────────────────────────────────────
@Composable
fun ResultSection(
    results        : List<PredictionResult>,
    capturedBitmap : Bitmap?,
    onRetry        : () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text(
            "HASIL ANALISIS",
            color         = TextMuted,
            fontSize      = 11.sp,
            fontFamily    = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(12.dp))

        if (results.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Hasil tidak ditemukan, coba scan ulang dengan pencahayaan lebih baik.",
                    color = TextMuted, textAlign = TextAlign.Center
                )
            }
        } else {
            TopResultCard(result = results[0])

            if (results[0].confidence < 60f) {
                Spacer(Modifier.height(10.dp))
                WarningBox("Confidence rendah (<60%). Pastikan foto jelas dan fokus pada daun yang terinfeksi.")
            }

            if (results.size > 1) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "PREDIKSI LAINNYA",
                    color         = TextMuted,
                    fontSize      = 11.sp,
                    fontFamily    = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(8.dp))
                results.drop(1).forEach {
                    OtherResultRow(it)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Action buttons ─────────────────────────────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick  = onRetry,
                modifier = Modifier.weight(1f).height(52.dp),
                shape    = RoundedCornerShape(12.dp),
                border   = BorderStroke(1.dp, GreenDim),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = GreenColor)
            ) {
                Text("SCAN ULANG", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            if (capturedBitmap != null && results.isNotEmpty()) {
                Button(
                    onClick  = { ShareUtils.shareResult(context, capturedBitmap, results[0]) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = GreenColor, contentColor = BgColor)
                ) {
                    Text("Share Result", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// HISTORY SCREEN
// ────────────────────────────────────────────────────────────────────────────────
@Composable
fun HistoryScreen(records: List<ScanRecord>, onDelete: (String) -> Unit) {
    var selectedRecord by remember { mutableStateOf<ScanRecord?>(null) }

    if (selectedRecord != null) {
        HistoryDetailScreen(
            record   = selectedRecord!!,
            onBack   = { selectedRecord = null },
            onDelete = { id -> onDelete(id); selectedRecord = null }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Header(
            title    = "Riwayat Scan",
            subtitle = "${records.size} hasil scan tersimpan"
        )

        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🌿", fontSize = 56.sp)
                    Spacer(Modifier.height(14.dp))
                    Text("Belum ada riwayat scan", color = TextMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text("Scan tanaman pertamamu!", color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier            = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(records, key = { it.id }) { record ->
                    HistoryItemCard(
                        record   = record,
                        onClick  = { selectedRecord = record },
                        onDelete = { onDelete(record.id) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
fun HistoryItemCard(record: ScanRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    val thumbBitmap = remember(record.imagePath) {
        runCatching { BitmapFactory.decodeFile(record.imagePath)?.asImageBitmap() }.getOrNull()
    }
    var showDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = SurfaceColor),
        border   = BorderStroke(1.dp, BorderColor)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Thumbnail
            Box(
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(Surface2),
                contentAlignment = Alignment.Center
            ) {
                if (thumbBitmap != null) {
                    Image(
                        bitmap       = thumbBitmap,
                        contentDescription = null,
                        modifier     = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text("🌿", fontSize = 28.sp)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.diseaseName,
                    color      = TextColor,
                    fontSize   = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${String.format("%.1f", record.confidence)}% confidence",
                    color      = TextMuted,
                    fontSize   = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DangerBadge(record.dangerLevel)
                    Text(record.dateFormatted, color = TextMuted, fontSize = 11.sp)
                }
            }

            IconButton(onClick = { showDialog = true }) {
                Text("Delete", fontSize = 18.sp)
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title            = { Text("Hapus Riwayat?", color = TextColor) },
            text             = { Text("Data scan ini akan dihapus permanen.", color = TextMuted) },
            confirmButton    = {
                TextButton(onClick = { onDelete(); showDialog = false }) {
                    Text("Hapus", color = DangerColor, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Batal", color = GreenColor)
                }
            },
            containerColor = SurfaceColor,
            shape          = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun HistoryDetailScreen(record: ScanRecord, onBack: () -> Unit, onDelete: (String) -> Unit) {
    val context   = LocalContext.current
    val imgBitmap = remember(record.imagePath) {
        runCatching { BitmapFactory.decodeFile(record.imagePath) }.getOrNull()
    }
    var showDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Back header
        Surface(color = SurfaceColor) {
            Row(
                modifier          = Modifier.fillMaxWidth().padding(4.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Text("←", color = GreenColor, fontSize = 22.sp)
                }
                Text("Detail Scan", color = GreenColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (imgBitmap != null) {
                    TextButton(onClick = { ShareUtils.shareResult(context, imgBitmap, record.toPredictionResult()) }) {
                        Text("Share Result", color = GreenColor, fontSize = 13.sp)
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            // Leaf photo
            if (imgBitmap != null) {
                Image(
                    bitmap       = imgBitmap.asImageBitmap(),
                    contentDescription = "Foto daun",
                    modifier     = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(8.dp))
            }

            Text(record.dateFormatted, color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(12.dp))

            TopResultCard(result = record.toPredictionResult())

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick  = { showDialog = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape    = RoundedCornerShape(12.dp),
                border   = BorderStroke(1.dp, DangerColor),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = DangerColor)
            ) {
                Text("Hapus dari Riwayat", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title            = { Text("Hapus Riwayat?", color = TextColor) },
            text             = { Text("Data scan ini akan dihapus permanen.", color = TextMuted) },
            confirmButton    = {
                TextButton(onClick = { onDelete(record.id); showDialog = false }) {
                    Text("Hapus", color = DangerColor, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Batal", color = GreenColor)
                }
            },
            containerColor = SurfaceColor,
            shape          = RoundedCornerShape(16.dp)
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// ENCYCLOPEDIA SCREEN
// ────────────────────────────────────────────────────────────────────────────────
@Composable
fun EncyclopediaScreen() {
    val allDiseases = remember {
        PlantClassifier.DISEASE_INFO.entries.toList()
    }
    val plantGroups = remember(allDiseases) {
        allDiseases.groupBy { entry ->
            entry.key.split("___")
                .firstOrNull()
                ?.replace("_", " ")
                ?.replaceFirstChar { it.uppercase() }
                ?: "Lainnya"
        }.toSortedMap()
    }

    var searchQuery     by remember { mutableStateOf("") }
    var selectedEntry   by remember { mutableStateOf<Map.Entry<String, Map<String, String>>?>(null) }

    if (selectedEntry != null) {
        EncyclopediaDetailScreen(
            className = selectedEntry!!.key,
            info      = selectedEntry!!.value,
            onBack    = { selectedEntry = null }
        )
        return
    }

    val filtered = if (searchQuery.isEmpty()) {
        plantGroups
    } else {
        plantGroups.mapValues { (_, diseases) ->
            diseases.filter { entry ->
                val name = entry.value["nama"] ?: ""
                name.contains(searchQuery, ignoreCase = true) ||
                        entry.key.contains(searchQuery, ignoreCase = true)
            }
        }.filter { it.value.isNotEmpty() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = SurfaceColor) {
            Column(modifier = Modifier.padding(20.dp, 14.dp, 20.dp, 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(GreenColor))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Ensiklopedia", color = GreenColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("${allDiseases.size} penyakit tanaman", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(10.dp))
                // Search bar
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface2)
                        .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔍", fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text("Cari nama penyakit...", color = TextMuted, fontSize = 14.sp)
                        }
                        BasicTextField(
                            value        = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier     = Modifier.fillMaxWidth(),
                            textStyle    = TextStyle(color = TextColor, fontSize = 14.sp),
                            singleLine   = true
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        TextButton(
                            onClick          = { searchQuery = "" },
                            contentPadding   = PaddingValues(0.dp),
                            modifier         = Modifier.size(24.dp)
                        ) {
                            Text("✕", color = TextMuted, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier            = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filtered.forEach { (plantName, diseases) ->
                item {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier          = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(GreenDim))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            plantName.uppercase(),
                            color         = TextMuted,
                            fontSize      = 11.sp,
                            fontFamily    = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        HorizontalDivider(
                            modifier  = Modifier.weight(1f),
                            color     = BorderColor,
                            thickness = 1.dp
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                items(diseases) { entry ->
                    EncyclopediaDiseaseCard(
                        className = entry.key,
                        info      = entry.value,
                        onClick   = { selectedEntry = entry }
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun EncyclopediaDiseaseCard(
    className : String,
    info      : Map<String, String>,
    onClick   : () -> Unit
) {
    val dangerLevel = info["bahaya"] ?: "Tidak ada"
    val isHealthy   = className.endsWith("healthy")
    val accent      = dangerLevelColor(dangerLevel)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceColor)
            .border(1.dp, if (isHealthy) BorderColor else accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(info["nama"] ?: className, color = TextColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                if (isHealthy) "Kondisi sehat" else "Bahaya: $dangerLevel",
                color = TextMuted, fontSize = 12.sp
            )
        }
        Text("›", color = TextMuted, fontSize = 20.sp)
    }
}

@Composable
fun EncyclopediaDetailScreen(
    className : String,
    info      : Map<String, String>,
    onBack    : () -> Unit
) {
    val dangerLevel = info["bahaya"] ?: "-"
    val cardColor   = dangerLevelColor(dangerLevel)
    val plantName   = className.split("___").firstOrNull()?.replace("_", " ") ?: ""

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = SurfaceColor) {
            Row(
                modifier          = Modifier.fillMaxWidth().padding(4.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Text("←", color = GreenColor, fontSize = 22.sp)
                }
                Text("Ensiklopedia", color = GreenColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(16.dp),
                colors   = CardDefaults.cardColors(containerColor = cardColor.copy(alpha = 0.10f)),
                border   = BorderStroke(1.dp, cardColor.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        plantName.uppercase(),
                        color      = cardColor,
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(info["nama"] ?: className, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    DangerBadge(dangerLevel)
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = BorderColor)
                    Spacer(Modifier.height(12.dp))
                    InfoRow("Penyebab",    info["penyebab"]  ?: "-")
                    Spacer(Modifier.height(10.dp))
                    InfoRow("Ciri-ciri",   info["ciri-ciri"] ?: "-")
                    Spacer(Modifier.height(10.dp))
                    InfoRow("Penanganan",  info["saran"]     ?: "-")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// SHARED UI COMPONENTS
// ────────────────────────────────────────────────────────────────────────────────

@Composable
fun DangerBadge(level: String) {
    val color = dangerLevelColor(level)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .border(0.5.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(level, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

fun dangerLevelColor(level: String): Color = when (level) {
    "Sangat Tinggi" -> Color(0xFF74070E)
    "Tinggi"        -> Color(0xFFDC4B2A)
    "Sedang"        -> Color(0xFF9E1B1F)
    "Rendah"        -> Color(0xFF145A3F)
    else            -> Color(0xFF1F6B4F) // "Tidak ada" = healthy green
}

@Composable
fun TopResultCard(result: PredictionResult) {
    val cardColor = dangerLevelColor(result.dangerLevel)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = cardColor.copy(alpha = 0.12f)),
        border   = BorderStroke(1.dp, cardColor.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("TERDETEKSI", color = cardColor, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(4.dp))
            Text(result.displayName, color = TextColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Confidence: ${String.format("%.1f", result.confidence)}%",
                color = TextMuted, fontSize = 13.sp, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress   = { result.confidence / 100f },
                modifier   = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(100.dp)),
                color      = cardColor,
                trackColor = BorderColor,
                strokeCap  = StrokeCap.Round,
            )
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = BorderColor)
            Spacer(Modifier.height(12.dp))
            InfoRow("Tingkat Bahaya", result.dangerLevel,  cardColor)
            Spacer(Modifier.height(6.dp))
            InfoRow("Penyebab",       result.cause)
            Spacer(Modifier.height(6.dp))
            InfoRow("Ciri-ciri",      result.symptom)
            Spacer(Modifier.height(6.dp))
            InfoRow("Penanganan",     result.solution)
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = TextColor) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label, color = TextMuted, fontSize = 12.sp,
            modifier = Modifier.width(110.dp), fontWeight = FontWeight.Medium
        )
        Text(value, color = valueColor, fontSize = 12.sp,
            modifier = Modifier.weight(1f), lineHeight = 18.sp)
    }
}

@Composable
fun OtherResultRow(result: PredictionResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface2)
            .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(result.displayName, color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text("${String.format("%.1f", result.confidence)}%",
            color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun WarningBox(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(WarningColor.copy(alpha = 0.1f))
            .border(1.dp, WarningColor.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("⚠️", fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(message, color = Color(0xFFFCD34D), fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
fun CornerBrackets() {
    Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        val stroke = 2.dp
        val size   = 24.dp
        Box(Modifier.align(Alignment.TopStart).size(size)
            .border(BorderStroke(stroke, GreenColor), RoundedCornerShape(topStart = 6.dp)))
        Box(Modifier.align(Alignment.TopEnd).size(size)
            .border(BorderStroke(stroke, GreenColor), RoundedCornerShape(topEnd = 6.dp)))
        Box(Modifier.align(Alignment.BottomStart).size(size)
            .border(BorderStroke(stroke, GreenColor), RoundedCornerShape(bottomStart = 6.dp)))
        Box(Modifier.align(Alignment.BottomEnd).size(size)
            .border(BorderStroke(stroke, GreenColor), RoundedCornerShape(bottomEnd = 6.dp)))
    }
}

@Composable
fun ScanningLine() {
    val transition = rememberInfiniteTransition(label = "scan")
    val offsetY    by transition.animateFloat(
        initialValue  = 0f,
        targetValue   = 500f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label         = "line"
    )
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .offset(y = offsetY.dp)
                .background(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Color.Transparent, GreenColor, Color.Transparent)
                    )
                )
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// HELPER: ImageProxy → Bitmap
// ────────────────────────────────────────────────────────────────────────────────
fun imageProxyToBitmap(image: ImageProxy): Bitmap {
    val buffer = image.planes[0].buffer
    val bytes  = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap   = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rotation = image.imageInfo.rotationDegrees
    return if (rotation != 0) {
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } else bitmap
}