package com.graden.editor

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

/**
 * What Cinema needs beyond the app: Depth Anything V2 Small (Apache-2.0) and YOLOX (Apache-2.0), exported by Qualcomm AI
 * Hub, and on Snapdragon phones Qualcomm's AI runtime from Maven Central. Fetched once, from their official sources and
 * checked against published hashes. On any other phone the models run on the CPU, more slowly, with the same results.
 */
object CinemaModels {
    private const val HUB = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models"
    private const val MAVEN = "https://repo1.maven.org/maven2/com/qualcomm/qti"
    private val DEPTH = CaptionModels.Spec("depth", "Depth Anything V2 Small", "", "depth_anything_v2-tflite-float.zip",
        "$HUB/depth_anything_v2/releases/v0.65.0/depth_anything_v2-tflite-float.zip", 91_862_800L, "98ac01249bb6e8e7fd9e75e73e82094ad61b7442fdd7a0f830c7d8aa5f15208f")
    private val YOLOX = CaptionModels.Spec("yolox", "YOLOX", "", "yolox-tflite-float.zip",
        "$HUB/yolox/releases/v0.65.0/yolox-tflite-float.zip", 33_398_602L, "ba671d7bca053e8c2ebae1ce237027721bba1b4c2b33f79d164d6fb7fa2b8276")
    private val RUNTIME = CaptionModels.Spec("qnn", "Qualcomm AI runtime", "", "qnn-runtime-2.51.0.aar",
        "$MAVEN/qnn-runtime/2.51.0/qnn-runtime-2.51.0.aar", 75_297_348L, "6388face1d5aa5e8958d745e5a547e92b5bec6fc332dfb1b438b1e17ffa02861")
    private val DELEGATE = CaptionModels.Spec("qnnd", "Qualcomm AI delegate", "", "qnn-litert-delegate-2.51.0.aar",
        "$MAVEN/qnn-litert-delegate/2.51.0/qnn-litert-delegate-2.51.0.aar", 660_377L, "b9cda7b45b434497b1158cbd3b0ed18b2b49ceec8061867721f166301033f156")

    /** This phone's Hexagon NPU generation, as the runtime's files name it; null when it has none the runtime supports. */
    @JvmField val hexagon: String? = run {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.uppercase() else ""
        when {
            Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" } -> null
            soc in setOf("SM8850", "SM8845") -> "V81"
            soc in setOf("SM8750", "SM8735") -> "V79"
            soc in setOf("SM8650", "SM8635", "SM7675", "QCS8625") -> "V75"
            soc in setOf("SM8550", "SM8475", "SM7550", "QCS8550") -> "V73"
            soc in setOf("SM8450", "SM7475") -> "V69"
            else -> null
        }
    }

    @JvmStatic fun model(c: Context, name: String) = File(CaptionModels.dir(c), name)
    @JvmStatic fun qnnDir(c: Context) = File(CaptionModels.dir(c), "qnn").apply { mkdirs() }
    private fun runtimeReady(c: Context) = File(qnnDir(c), "ok-$hexagon").isFile

    fun installed(c: Context) = model(c, "depth.tflite").isFile && model(c, "yolox.tflite").isFile && (hexagon == null || runtimeReady(c))

    /** The download on this phone, in MB. */
    val downloadMb get() = if (hexagon != null) 200 else 125

    /** Fetches and unpacks what this phone needs, reporting 0..1. */
    fun install(c: Context, progress: (Float) -> Unit, cancelled: () -> Boolean): Result<Unit> = runCatching {
        val parts = listOf(DEPTH to "depth.tflite", YOLOX to "yolox.tflite")
        val qnn = if (hexagon != null && !runtimeReady(c)) listOf(RUNTIME, DELEGATE) else emptyList()
        val todo = parts.filter { !model(c, it.second).isFile }.map { it.first } + qnn
        val total = todo.sumOf { it.size }.toFloat().coerceAtLeast(1f)
        var done = 0L
        for (s in todo) {
            val base = done
            CaptionModels.download(c, s, { p -> progress(((base + p * s.size) / total).coerceAtMost(0.99f)) }, cancelled).getOrThrow()
            done += s.size
        }
        for ((s, name) in parts) if (!model(c, name).isFile) {
            val z = CaptionModels.file(c, s)
            ZipFile(z).use { zip ->
                val e = zip.entries().asSequence().first { it.name.endsWith(".tflite") }
                val part = File(model(c, name).path + ".part")
                zip.getInputStream(e).use { i -> part.outputStream().use { i.copyTo(it, 1 shl 16) } }
                check(part.renameTo(model(c, name))) { "Couldn't keep the model" }
            }
            z.delete()
        }
        if (qnn.isNotEmpty()) {
            val v = hexagon!!
            val want = setOf("libQnnSystem.so", "libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnHtp${v}Stub.so", "libQnnHtp${v}Skel.so", "libQnnTFLiteDelegate.so")
            for (s in qnn) {
                val f = CaptionModels.file(c, s)
                ZipFile(f).use { zip ->
                    for (e in zip.entries()) {
                        val n = e.name.substringAfterLast('/')
                        if (n in want && e.name.contains("arm64-v8a")) zip.getInputStream(e).use { i -> File(qnnDir(c), n).outputStream().use { i.copyTo(it, 1 shl 16) } }
                    }
                }
                f.delete()
            }
            File(qnnDir(c), "ok-$v").writeText("2.51.0")
        }
        progress(1f)
    }
}
