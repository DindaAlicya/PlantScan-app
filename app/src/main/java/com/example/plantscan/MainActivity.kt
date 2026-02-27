package com.example.plantscan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

// ================================================================
// MAIN ACTIVITY
// ================================================================
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
            PlantScanTheme {
                PlantScanApp()
            }
        }
    }
}

@Composable
fun PlantScanApp() {
    val context        = LocalContext.current
    val scope          = rememberCoroutineScope()
    val classifier     = remember { PlantClassifier(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var results    by remember { mutableStateOf<List<PredictionResult>?>(null) }
    var isLoading  by remember { mutableStateOf(false) }
    var statusMsg  by remember { mutableStateOf("Arahkan kamera ke daun tanaman") }
    var showResult by remember { mutableStateOf(false) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    DisposableEffect(Unit) {
        onDispose {
            classifier.close()
            cameraExecutor.shutdown()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(BgColor)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header()
            if (!showResult) {
                CameraSection(
                    isLoading           = isLoading,
                    statusMsg           = statusMsg,
                    onImageCaptureReady = { imageCapture = it },
                    onScanClick         = {
                        if (!isLoading) {
                            imageCapture?.let { capture ->
                                isLoading = true
                                statusMsg = "analisis gambar..."
                                capture.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            scope.launch {
                                                val bitmap = imageProxyToBitmap(image)
                                                image.close()
                                                val output = withContext(Dispatchers.Default) {
                                                    classifier.classify(bitmap)
                                                }
                                                results    = output
                                                showResult = true
                                                isLoading  = false
                                                statusMsg  = "Scan selesai"
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
                    results = results ?: emptyList(),
                    onRetry = {
                        showResult = false
                        results    = null
                        statusMsg  = "Arahkan kamera ke daun tanaman"
                    }
                )
            }
        }
    }
}

@Composable
fun Header() {
    Surface(color = SurfaceColor) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(GreenColor))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("PlantScan", color = GreenColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Deteksi Penyakit Tanaman",
                    color      = TextMuted,
                    fontSize   = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun CameraSection(
    isLoading          : Boolean,
    statusMsg          : String,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onScanClick        : () -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier            = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Viewfinder
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

            // Corner brackets
            CornerBrackets()

            // Scanning line
            if (isLoading) ScanningLine()
        }

        Spacer(Modifier.height(12.dp))

        // Status bar
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

        // Tombol Scan
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

@Composable
fun ResultSection(results: List<PredictionResult>, onRetry: () -> Unit) {
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
                Text("Hasil tidak ditemukan, class belum terupdate atau coba scan ulang.", color = TextMuted, textAlign = TextAlign.Center)
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

        OutlinedButton(
            onClick  = onRetry,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(12.dp),
            border   = BorderStroke(1.dp, GreenDim),
            colors   = ButtonDefaults.outlinedButtonColors(contentColor = GreenColor)
        ) {
            Text("SCAN ULANG", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun TopResultCard(result: PredictionResult) {
    val cardColor = when (result.dangerLevel) {
        "Sangat Tinggi" -> DangerColor
        "Tinggi"        -> Color(0xFFDC4B2A)
        "Sedang"        -> WarningColor
        "Rendah"        -> GreenDim
        else            -> GreenColor
    }
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
            InfoRow("Tingkat Bahaya", result.dangerLevel, cardColor)
            Spacer(Modifier.height(6.dp))
            InfoRow("Penyebab",       result.cause)
            Spacer(Modifier.height(6.dp))
            InfoRow("Gejala",         result.symptom)
            Spacer(Modifier.height(6.dp))
            InfoRow("Penanganan",  result.solution)
        }
    }
}

// ================================================================
// INFO ROW
// ================================================================
@Composable
fun InfoRow(label: String, value: String, valueColor: Color = TextColor) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TextMuted, fontSize = 12.sp,
            modifier = Modifier.width(110.dp), fontWeight = FontWeight.Medium)
        Text(value, color = valueColor, fontSize = 12.sp,
            modifier = Modifier.weight(1f), lineHeight = 18.sp)
    }
}

// ================================================================
// OTHER RESULT ROW
// ================================================================
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

// ================================================================
// WARNING BOX
// ================================================================
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

// ================================================================
// HELPER: ImageProxy → Bitmap
// ================================================================
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