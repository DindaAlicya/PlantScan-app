package com.example.plantscan.ml

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class PredictionResult(
    val className   : String,
    val displayName : String,
    val confidence  : Float,
    val dangerLevel : String,
    val cause       : String,
    val symptom     : String,
    val solution    : String,
)

class PlantClassifier(private val context: Context) {

    companion object {
        private const val MODEL_FILE     = "plant_disease_model.tflite"
        private const val CLASS_MAP_FILE = "class_indices.json"
        private const val IMG_SIZE       = 224
        private const val NUM_CLASSES    = 38
        private const val TOP_K          = 3
    }

    private val diseaseInfo = mapOf(

        "Apple___Apple_scab" to mapOf(
            "nama" to "Kudis apel",
            "bahaya" to "Tinggi",
            "penyebab" to "Penyakit ini disebabkan oleh jamur Venturia inaequalis yang berkembang pada kondisi lembap dengan suhu sedang. Spora jamur menyebar melalui angin dan percikan air hujan lalu menginfeksi daun dan buah muda.",
            "ciri-ciri" to "Muncul bercak gelap tidak beraturan pada daun dan buah. Daun dapat menguning lalu rontok lebih cepat, sementara buah menjadi kasar, retak, dan pertumbuhannya terhambat.",
            "saran" to "Lakukan pemangkasan bagian terinfeksi dan jaga sirkulasi udara antar tanaman. Gunakan fungisida berbahan aktif myclobutanil secara berkala sesuai dosis anjuran."
        ),

        "Apple___Black_rot" to mapOf(
            "nama" to "Black rot apple",
            "bahaya" to "Tinggi",
            "penyebab" to "Penyakit ini disebabkan oleh jamur Botryosphaeria obtusa yang bertahan pada sisa tanaman terinfeksi. Infeksi meningkat pada kondisi lembap dan kebun dengan sanitasi buruk.",
            "ciri-ciri" to "Daun menunjukkan bercak coklat dengan tepi lebih gelap. Buah membusuk secara bertahap, menghitam, lalu mengering seperti mumi.",
            "saran" to "Segera buang bagian tanaman yang terinfeksi dan lakukan sanitasi kebun. Gunakan fungisida captan atau fungisida lain yang direkomendasikan."
        ),

        "Apple___Cedar_apple_rust" to mapOf(
            "nama" to "Karat daun apel",
            "bahaya" to "Sedang",
            "penyebab" to "Penyakit ini disebabkan oleh jamur Gymnosporangium juniperi-virginianae yang membutuhkan dua inang yaitu apel dan cedar untuk menyelesaikan siklus hidupnya.",
            "ciri-ciri" to "Muncul bercak oranye kekuningan pada permukaan atas daun. Pada bagian bawah daun terbentuk tonjolan kecil yang mengandung spora.",
            "saran" to "Jauhkan tanaman apel dari pohon cedar dan lakukan penyemprotan fungisida mancozeb saat musim hujan."
        ),

        "Apple___healthy" to mapOf(
            "nama" to "Apel sehat",
            "bahaya" to "Tidak ada",
            "penyebab" to "Tanaman tidak menunjukkan adanya infeksi patogen atau gangguan fisiologis.",
            "ciri-ciri" to "Daun berwarna hijau segar tanpa bercak atau kerusakan. Pertumbuhan tanaman normal dan buah berkembang dengan baik.",
            "saran" to "Lanjutkan perawatan rutin seperti penyiraman teratur, pemupukan seimbang, dan pemangkasan ringan bila diperlukan."
        ),

        "Blueberry___healthy" to mapOf(
            "nama" to "Blueberry sehat",
            "bahaya" to "Tidak ada",
            "penyebab" to "Tanaman berada dalam kondisi fisiologis yang normal tanpa adanya infeksi.",
            "ciri-ciri" to "Daun hijau merata tanpa bercak. Pertumbuhan stabil dan tidak terlihat tanda stres.",
            "saran" to "Pertahankan pola penyiraman dan pemupukan yang seimbang untuk menjaga kesehatan tanaman."
        ),

        "Cherry_(including_sour)___Powdery_mildew" to mapOf(
            "nama" to "Embun tepung ceri",
            "bahaya" to "Sedang",
            "penyebab" to "Penyakit ini disebabkan oleh jamur Podosphaera clandestina yang berkembang pada kondisi lembap dengan sirkulasi udara buruk.",
            "ciri-ciri" to "Terlihat lapisan putih seperti tepung pada permukaan daun dan tunas muda. Daun dapat melengkung dan pertumbuhan terganggu.",
            "saran" to "Perbaiki sirkulasi udara dan gunakan fungisida sulfur sesuai anjuran."
        ),

        "Cherry_(including_sour)___healthy" to mapOf(
            "nama" to "Ceri sehat",
            "bahaya" to "Tidak ada",
            "penyebab" to "Tanaman tidak terinfeksi patogen dan tumbuh optimal.",
            "ciri-ciri" to "Daun hijau segar dan tidak terdapat bercak. Buah berkembang normal.",
            "saran" to "Lanjutkan perawatan rutin dan pemangkasan ringan bila diperlukan."
        ),

        "Corn_(maize)___Cercospora_leaf_spot Gray_leaf_spot" to mapOf(
            "nama" to "Bercak abu-abu jagung",
            "bahaya" to "Tinggi",
            "penyebab" to "Disebabkan oleh jamur Cercospora zeae-maydis yang berkembang pada kondisi lembap dan suhu hangat.",
            "ciri-ciri" to "Bercak abu-abu memanjang sejajar tulang daun yang dapat menyatu dan merusak jaringan daun.",
            "saran" to "Lakukan rotasi tanaman dan aplikasikan fungisida azoxystrobin sesuai anjuran."
        ),

        "Corn_(maize)___Common_rust_" to mapOf(
            "nama" to "Karat daun jagung",
            "bahaya" to "Sedang",
            "penyebab" to "Disebabkan oleh jamur Puccinia sorghi yang menyebar melalui angin.",
            "ciri-ciri" to "Muncul pustula coklat kemerahan pada kedua sisi daun.",
            "saran" to "Gunakan varietas tahan dan aplikasikan fungisida triazole bila diperlukan."
        ),

        "Corn_(maize)___Northern_Leaf_Blight" to mapOf(
            "nama" to "Hawar daun jagung",
            "bahaya" to "Tinggi",
            "penyebab" to "Disebabkan oleh jamur Exserohilum turcicum yang berkembang pada kelembapan tinggi.",
            "ciri-ciri" to "Lesi panjang abu-abu kehijauan pada daun yang mengurangi luas fotosintesis.",
            "saran" to "Gunakan varietas tahan dan aplikasikan fungisida sesuai dosis anjuran."
        ),

        "Corn_(maize)___healthy" to mapOf(
            "nama" to "Jagung sehat",
            "bahaya" to "Tidak ada",
            "penyebab" to "Tanaman tidak terinfeksi penyakit.",
            "ciri-ciri" to "Daun hijau sehat dan pertumbuhan normal.",
            "saran" to "Pertahankan pemupukan dan irigasi yang tepat."
        ),

        "Grape___Black_rot" to mapOf(
            "nama" to "Busuk hitam anggur",
            "bahaya" to "Tinggi",
            "penyebab" to "Disebabkan oleh jamur Guignardia bidwellii yang berkembang pada kondisi lembap.",
            "ciri-ciri" to "Bercak coklat pada daun dan buah mengkerut serta menghitam.",
            "saran" to "Pangkas bagian terinfeksi dan gunakan fungisida mancozeb."
        ),

        "Grape___Esca_(Black_Measles)" to mapOf(
            "nama" to "Esca anggur",
            "bahaya" to "Sangat tinggi",
            "penyebab" to "Disebabkan oleh kompleks jamur yang menyerang jaringan kayu tanaman.",
            "ciri-ciri" to "Daun belang kuning coklat dan buah berbintik hitam.",
            "saran" to "Pangkas dan bakar bagian terinfeksi serta hindari luka saat pemangkasan."
        ),

        "Grape___Leaf_blight_(Isariopsis_Leaf_Spot)" to mapOf(
            "nama" to "Hawar daun anggur",
            "bahaya" to "Sedang",
            "penyebab" to "Disebabkan oleh jamur Pseudocercospora vitis.",
            "ciri-ciri" to "Bercak coklat tidak teratur pada daun tua.",
            "saran" to "Gunakan fungisida berbahan tembaga dan pastikan drainase baik."
        ),

        "Grape___healthy" to mapOf(
            "nama" to "Anggur sehat",
            "bahaya" to "Tidak ada",
            "penyebab" to "Tanaman tumbuh optimal tanpa infeksi.",
            "ciri-ciri" to "Daun hijau segar dan tidak ada bercak.",
            "saran" to "Lanjutkan perawatan rutin."
        ),
        "Orange___Haunglongbing_(Citrus_greening)" to mapOf(
            "nama" to "Daun jeruk menguning",
            "bahaya" to "Sangat tinggi",
            "penyebab" to "Penyakit ini disebabkan oleh bakteri Candidatus Liberibacter yang ditularkan oleh serangga kutu loncat jeruk. Penyebaran berlangsung cepat terutama pada kebun dengan populasi vektor tinggi.",
            "ciri-ciri" to "Daun menunjukkan belang kuning tidak simetris dan tulang daun tetap hijau. Buah berukuran kecil, rasa pahit, dan bentuk tidak normal.",
            "saran" to "Tidak tersedia obat yang efektif. Cabut dan musnahkan tanaman terinfeksi serta kendalikan populasi serangga vektor secara rutin."
        ),

        "Peach___Bacterial_spot" to mapOf(
            "nama" to "Bercak bakteri peach",
            "bahaya" to "Tinggi",
            "penyebab" to "Disebabkan oleh bakteri Xanthomonas arboricola yang berkembang pada kondisi lembap dan percikan air hujan.",
            "ciri-ciri" to "Muncul bercak kecil basah pada daun yang kemudian mengering dan berlubang. Buah dapat mengalami lesi kasar.",
            "saran" to "Gunakan bakterisida berbahan tembaga dan hindari penyiraman dari atas yang memicu percikan air."
        ),

        "Peach___healthy" to mapOf(
            "nama" to "Peach sehat",
            "bahaya" to "Tidak ada",
            "penyebab" to "Tanaman berada dalam kondisi sehat tanpa infeksi patogen.",
            "ciri-ciri" to "Daun hijau bersih tanpa bercak dan pertumbuhan normal.",
            "saran" to "Pertahankan pemupukan dan penyiraman sesuai kebutuhan tanaman."
        ),

        "Pepper,_bell___Bacterial_spot" to mapOf(
            "nama" to "Bercak bakteri paprika",
            "bahaya" to "Tinggi",
            "penyebab" to "Disebabkan oleh bakteri Xanthomonas campestris yang menyebar melalui benih dan percikan air.",
            "ciri-ciri" to "Bercak kecil berair pada daun yang kemudian mengering dan berlubang. Buah dapat menunjukkan bercak kasar.",
            "saran" to "Gunakan benih bersertifikat dan aplikasikan bakterisida tembaga secara teratur."
        ),

        "Pepper,_bell___healthy" to mapOf(
            "nama" to "Paprika sehat",
            "bahaya" to "Tidak ada",
            "penyebab" to "Tanaman tumbuh dalam kondisi optimal tanpa infeksi.",
            "ciri-ciri" to "Daun hijau segar tanpa bercak atau kerusakan.",
            "saran" to "Lanjutkan perawatan rutin dan pengendalian hama secara preventif."
        ),

        "Potato___Early_blight" to mapOf(
            "nama" to "Hawar awal kentang",
            "bahaya" to "Sedang",
            "penyebab" to "Disebabkan oleh jamur Alternaria solani yang berkembang pada kondisi lembap dan suhu hangat.",
            "ciri-ciri" to "Bercak coklat dengan pola lingkaran konsentris pada daun tua. Daun dapat mengering dan rontok.",
            "saran" to "Lakukan rotasi tanaman dan aplikasikan fungisida chlorothalonil sesuai dosis anjuran."
        ),

        "Potato___Late_blight" to mapOf(
            "nama" to "Hawar akhir kentang",
            "bahaya" to "Sangat tinggi",
            "penyebab" to "Disebabkan oleh organisme mirip jamur Phytophthora infestans yang menyebar cepat pada kondisi lembap dan dingin.",
            "ciri-ciri" to "Bercak hijau gelap berair yang cepat meluas dan berubah menjadi coklat kehitaman. Umbi dapat membusuk.",
            "saran" to "Segera lakukan penyemprotan fungisida metalaxyl atau mancozeb dan pastikan drainase lahan baik."
        ),

        "Potato___healthy" to mapOf(
            "nama" to "Kentang sehat",
            "bahaya" to "Tidak ada",
            "penyebab" to "Tanaman tidak menunjukkan tanda infeksi atau gangguan.",
            "ciri-ciri" to "Daun hijau merata dan pertumbuhan normal.",
            "saran" to "Pertahankan perawatan rutin dan sanitasi lahan."
        ),

        "Raspberry___healthy" to mapOf(
            "nama" to "Raspberry sehat",
            "bahaya" to "Tidak ada",
            "penyebab" to "Tanaman berada dalam kondisi fisiologis yang baik.",
            "ciri-ciri" to "Daun hijau tanpa bercak dan pertumbuhan stabil.",
            "saran" to "Lanjutkan perawatan rutin serta pemupukan seimbang."
        ),

        "Soybean___healthy" to mapOf(
            "nama" to "Kedelai sehat",
            "bahaya" to "Tidak ada",
            "penyebab" to "Tanaman tumbuh tanpa infeksi patogen.",
            "ciri-ciri" to "Daun hijau bersih dan pertumbuhan normal.",
            "saran" to "Pertahankan irigasi dan pemupukan yang tepat."
        ),

        "Squash___Powdery_mildew" to mapOf(
            "nama" to "Embun tepung labu",
            "bahaya" to "Sedang",
            "penyebab" to "Disebabkan oleh jamur Podosphaera xanthii yang berkembang pada kondisi lembap dengan sirkulasi udara buruk.",
            "ciri-ciri" to "Lapisan putih seperti tepung pada permukaan daun. Daun mengering dan rontok jika infeksi parah.",
            "saran" to "Gunakan fungisida sulfur atau larutan baking soda dan perbaiki sirkulasi udara."
        ),

        "Strawberry___Leaf_scorch" to mapOf(
            "nama" to "Daun gosong stroberi",
            "bahaya" to "Sedang",
            "penyebab" to "Disebabkan oleh jamur Diplocarpon earlianum yang berkembang pada kondisi lembap.",
            "ciri-ciri" to "Bercak ungu kecil pada daun yang kemudian menyatu dan membuat tepi daun mengering.",
            "saran" to "Gunakan fungisida captan dan hindari penyiraman berlebihan pada daun."
        ),

        "Strawberry___healthy" to mapOf(
            "nama" to "Stroberi sehat",
            "bahaya" to "Tidak ada",
            "penyebab" to "Tanaman dalam kondisi sehat tanpa infeksi.",
            "ciri-ciri" to "Daun hijau segar dan tidak terdapat bercak.",
            "saran" to "Lanjutkan perawatan rutin dan pemupukan teratur."
        ),

        "Tomato___Bacterial_spot" to mapOf(
            "nama" to "Bercak bakteri tomat",
            "bahaya" to "Tinggi",
            "penyebab" to "Disebabkan oleh bakteri Xanthomonas vesicatoria yang menyebar melalui percikan air dan benih terinfeksi.",
            "ciri-ciri" to "Bercak kecil berair pada daun dan buah yang kemudian menghitam.",
            "saran" to "Gunakan benih bebas penyakit dan aplikasikan bakterisida tembaga."
        ),

        "Tomato___Early_blight" to mapOf(
            "nama" to "Bercak Kering tomat",
            "bahaya" to "Sedang",
            "penyebab" to "Disebabkan oleh jamur Alternaria solani yang berkembang pada kondisi lembap.",
            "ciri-ciri" to "Bercak coklat dengan pola lingkaran pada daun bawah.",
            "saran" to "Pangkas daun bawah dan aplikasikan fungisida chlorothalonil."
        ),

        "Tomato___Late_blight" to mapOf(
            "nama" to "Busuk daun tomat",
            "bahaya" to "Sangat tinggi",
            "penyebab" to "Disebabkan oleh Phytophthora infestans yang menyebar cepat pada kelembapan tinggi.",
            "ciri-ciri" to "Bercak hijau gelap berair yang cepat meluas dan merusak seluruh tanaman.",
            "saran" to "Segera semprot fungisida metalaxyl dan kurangi kelembapan lingkungan."
        ),

        "Tomato___Leaf_Mold" to mapOf(
            "nama" to "Jamur daun tomat",
            "bahaya" to "Sedang",
            "penyebab" to "Disebabkan oleh jamur Passalora fulva yang berkembang pada kondisi lembap di rumah kaca.",
            "ciri-ciri" to "Bercak kuning di bagian atas daun dan lapisan abu-abu di bagian bawah.",
            "saran" to "Perbaiki ventilasi dan gunakan fungisida sesuai rekomendasi."
        ),

        "Tomato___Septoria_leaf_spot" to mapOf(
            "nama" to "Bercak septoria tomat",
            "bahaya" to "Sedang",
            "penyebab" to "Disebabkan oleh jamur Septoria lycopersici yang berkembang pada kondisi lembap.",
            "ciri-ciri" to "Bercak kecil bulat dengan titik gelap di tengah pada daun bagian bawah.",
            "saran" to "Pangkas daun terinfeksi dan gunakan fungisida mancozeb."
        ),

        "Tomato___Spider_mites Two-spotted_spider_mite" to mapOf(
            "nama" to "Tungau laba-laba tomat",
            "bahaya" to "Sedang",
            "penyebab" to "Disebabkan oleh tungau Tetranychus urticae yang berkembang pada kondisi panas dan kering.",
            "ciri-ciri" to "Daun berbintik kuning dan terlihat jaring halus di bawah daun.",
            "saran" to "Gunakan akarisida seperti abamectin dan tingkatkan kelembapan sekitar tanaman."
        ),

        "Tomato___Target_Spot" to mapOf(
            "nama" to "Bercak target tomat",
            "bahaya" to "Sedang",
            "penyebab" to "Disebabkan oleh jamur Corynespora cassiicola yang berkembang pada kelembapan tinggi.",
            "ciri-ciri" to "Bercak coklat berpola cincin konsentris pada daun.",
            "saran" to "Gunakan fungisida azoxystrobin dan hindari penyiraman pada malam hari."
        ),

        "Tomato___Tomato_Yellow_Leaf_Curl_Virus" to mapOf(
            "nama" to "Daun tomat kuning keriting",
            "bahaya" to "Sangat tinggi",
            "penyebab" to "Disebabkan oleh virus TYLCV yang ditularkan oleh kutu kebul.",
            "ciri-ciri" to "Daun menggulung ke atas dan menguning. Tanaman menjadi kerdil dan produksi menurun drastis.",
            "saran" to "Cabut tanaman terinfeksi dan kendalikan populasi kutu kebul secara rutin."
        ),

        "Tomato___Tomato_mosaic_virus" to mapOf(
            "nama" to "Daun tomat belang atau mosaik",
            "bahaya" to "Tinggi",
            "penyebab" to "Disebabkan oleh virus ToMV yang menyebar melalui alat dan benih terkontaminasi.",
            "ciri-ciri" to "Daun belang kuning hijau tidak merata dan mengkerut.",
            "saran" to "Gunakan benih bersertifikat dan sterilkan alat kebun secara rutin."
        ),

        "Tomato___healthy" to mapOf(
            "nama" to "Tomat sehat",
            "bahaya" to "Tidak ada",
            "penyebab" to "Tanaman tumbuh optimal tanpa infeksi patogen.",
            "ciri-ciri" to "Daun hijau bersih tanpa bercak dan pertumbuhan normal.",
            "saran" to "Lanjutkan perawatan rutin dan pemupukan seimbang."
        )

    )

    private val interpreter : Interpreter
    private val classIndices: Map<Int, String>

    init {
        interpreter  = Interpreter(loadModelFile(), Interpreter.Options().apply {
            setNumThreads(4)
        })
        classIndices = loadClassIndices()
    }

    @Throws(IOException::class)
    private fun loadModelFile(): java.nio.MappedByteBuffer {
        val fd          = context.assets.openFd(MODEL_FILE)
        val inputStream = java.io.FileInputStream(fd.fileDescriptor)
        return inputStream.channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        )
    }

    private fun loadClassIndices(): Map<Int, String> {
        val json    = context.assets.open(CLASS_MAP_FILE).bufferedReader().readText()
        val jsonObj = JSONObject(json)
        val map     = mutableMapOf<Int, String>()
        jsonObj.keys().forEach { key -> map[key.toInt()] = jsonObj.getString(key) }
        return map
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val resized    = Bitmap.createScaledBitmap(bitmap, IMG_SIZE, IMG_SIZE, true)
        val byteBuffer = ByteBuffer.allocateDirect(4 * IMG_SIZE * IMG_SIZE * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(IMG_SIZE * IMG_SIZE)
        resized.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)
        for (pixel in pixels) {
            byteBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            byteBuffer.putFloat(((pixel shr 8)  and 0xFF) / 255.0f)
            byteBuffer.putFloat((pixel           and 0xFF) / 255.0f)
        }
        return byteBuffer
    }

    fun classify(bitmap: Bitmap): List<PredictionResult> {
        val input  = preprocessBitmap(bitmap)
        val output = Array(1) { FloatArray(NUM_CLASSES) }
        interpreter.run(input, output)
        return output[0]
            .mapIndexed { index, confidence -> Pair(index, confidence * 100f) }
            .sortedByDescending { it.second }
            .take(TOP_K)
            .map { (index, confidence) ->
                val className = classIndices[index] ?: "unknown_$index"
                val info      = diseaseInfo[className]
                PredictionResult(
                    className   = className,
                    displayName = info?.get("nama")     ?: className.replace("_", " "),
                    confidence  = confidence,
                    dangerLevel = info?.get("bahaya")   ?: "-",
                    cause       = info?.get("penyebab") ?: "-",
                    symptom     = info?.get("gejala")   ?: "-",
                    solution    = info?.get("saran")    ?: "Konsultasikan dengan penyuluh pertanian.",
                )
            }
    }

    fun close() = interpreter.close()
}
