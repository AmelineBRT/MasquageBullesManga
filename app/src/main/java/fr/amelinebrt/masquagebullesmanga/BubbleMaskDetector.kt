package fr.amelinebrt.masquagebullesmanga

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Local YOLO11n balloon segmentation. The ONNX weights are downloaded once and
 * kept in app-private storage. The original page never leaves the device.
 */
internal object BubbleMaskDetector {
    private const val MODEL_SIZE = 1024
    private const val PROTO_SIZE = 256
    private const val CHANNELS = 32
    private const val MODEL_URL =
        "https://huggingface.co/mednasserallah/manga109-segmentation-bubble-onnx/resolve/main/manga109_segmentation_bubble_1024.onnx"

    data class Result(val bitmap: Bitmap, val bubbleCount: Int, val elapsedMs: Long)

    private interface BoxLike {
        val left: Float
        val top: Float
        val right: Float
        val bottom: Float
        val w: Float
        val h: Float
    }

    private data class Box(
        val cx: Float,
        val cy: Float,
        override val w: Float,
        override val h: Float,
        val score: Float,
        val index: Int
    ) : BoxLike {
        override val left get() = cx - w / 2f
        override val top get() = cy - h / 2f
        override val right get() = cx + w / 2f
        override val bottom get() = cy + h / 2f
    }

    suspend fun run(context: Context, source: Bitmap): Result {
        val started = System.currentTimeMillis()
        val modelFile = File(context.filesDir, "bubble-segmentation-1024.onnx")
        if (!modelFile.exists() || modelFile.length() < 1_000_000L) downloadModel(modelFile)

        val width = source.width
        val height = source.height
        val scale = min(MODEL_SIZE.toFloat() / width, MODEL_SIZE.toFloat() / height)
        val resizedWidth = max(1, (width * scale).roundToInt())
        val resizedHeight = max(1, (height * scale).roundToInt())
        val padLeft = (MODEL_SIZE - resizedWidth) / 2
        val padTop = (MODEL_SIZE - resizedHeight) / 2

        val resized = Bitmap.createScaledBitmap(source, resizedWidth, resizedHeight, true)
        val canvasBitmap = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(canvasBitmap).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(resized, padLeft.toFloat(), padTop.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (resized !== source) resized.recycle()

        val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
        canvasBitmap.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        canvasBitmap.recycle()
        val input = FloatArray(3 * MODEL_SIZE * MODEL_SIZE)
        val plane = MODEL_SIZE * MODEL_SIZE
        for (i in pixels.indices) {
            val p = pixels[i]
            input[i] = Color.red(p) / 255f
            input[plane + i] = Color.green(p) / 255f
            input[2 * plane + i] = Color.blue(p) / 255f
        }

        val env = OrtEnvironment.getEnvironment()
        val options = ai.onnxruntime.OrtSession.SessionOptions()
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        val session = try {
            env.createSession(modelFile.absolutePath, options)
        } finally {
            options.close()
        }

        val maskGrid = BooleanArray(MODEL_SIZE * MODEL_SIZE)
        var detectedCount = 0
        try {
            val inputName = session.inputNames.first()
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, MODEL_SIZE.toLong(), MODEL_SIZE.toLong())
            ).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { output ->
                    val output0 = output[0].value as Array<*>
                    val features = output0[0] as Array<*>
                    val feature = Array(37) { features[it] as FloatArray }

                    val output1 = output[1].value as Array<*>
                    val proto = output1[0] as Array<*>
                    val protoRows = Array(CHANNELS) { channel ->
                        val rows = proto[channel] as Array<*>
                        Array(PROTO_SIZE) { y -> rows[y] as FloatArray }
                    }

                    val candidates = ArrayList<Box>()
                    val count = feature[4].size
                    for (i in 0 until count) {
                        val score = feature[4][i]
                        if (score >= 0.20f) { // Lower threshold to recover smaller/less confident balloons.
                            val w = feature[2][i]
                            val h = feature[3][i]
                            if (w > 2f && h > 2f) {
                                candidates.add(Box(feature[0][i], feature[1][i], w, h, score, i))
                            }
                        }
                    }
                    candidates.sortByDescending { it.score }
                    val selected = ArrayList<Box>()
                    for (candidate in candidates) {
                        if (selected.none { iou(it, candidate) > 0.50f }) {
                            selected.add(candidate)
                            if (selected.size >= 80) break
                        }
                    }

                    for (box in selected) {
                        val left = max(0, (box.left / 4f).toInt())
                        val top = max(0, (box.top / 4f).toInt())
                        val right = min(PROTO_SIZE - 1, (box.right / 4f).toInt())
                        val bottom = min(PROTO_SIZE - 1, (box.bottom / 4f).toInt())
                        if (right <= left || bottom <= top) continue
                        val coeff = FloatArray(CHANNELS) { c -> feature[5 + c][box.index] }
                        var maskPixels = 0
                        for (py in top..bottom) {
                            for (px in left..right) {
                                var logit = 0f
                                for (c in 0 until CHANNELS) logit += coeff[c] * protoRows[c][py][px]
                                val probability = 1f / (1f + exp(-logit))
                                if (probability > 0.5f) {
                                    val x0 = px * 4
                                    val y0 = py * 4
                                    for (dy in 0..3) {
                                        val yy = y0 + dy
                                        if (yy !in 0 until MODEL_SIZE) continue
                                        val offset = yy * MODEL_SIZE
                                        for (dx in 0..3) {
                                            val xx = x0 + dx
                                            if (xx in 0 until MODEL_SIZE) {
                                                maskGrid[offset + xx] = true
                                                maskPixels++
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (maskPixels > 30) detectedCount++
                    }
                }
            }
        } finally {
            session.close()
        }

        // Contract the segmentation slightly so the white fill stays inside the
        // balloon outline instead of covering the black border and nearby artwork.
        val tightenedMask = erodeMask(maskGrid, radius = 3)

        val original = IntArray(width * height)
        source.getPixels(original, 0, width, 0, 0, width, height)
        for (y in 0 until height) {
            val modelY = (y * scale + padTop).toInt().coerceIn(0, MODEL_SIZE - 1)
            val sourceRow = y * width
            val modelRow = modelY * MODEL_SIZE
            for (x in 0 until width) {
                val modelX = (x * scale + padLeft).toInt().coerceIn(0, MODEL_SIZE - 1)
                if (tightenedMask[modelRow + modelX]) original[sourceRow + x] = Color.WHITE
            }
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(original, 0, width, 0, 0, width, height)
        return Result(result, detectedCount, System.currentTimeMillis() - started)
    }

    /**
     * Removes a narrow border from the predicted mask. A separable square erosion
     * is used to keep the outline inside the balloon and reduce spill into nearby art.
     */
    private fun erodeMask(mask: BooleanArray, radius: Int): BooleanArray {
        if (radius <= 0) return mask
        val horizontal = BooleanArray(mask.size)
        val result = BooleanArray(mask.size)

        for (y in 0 until MODEL_SIZE) {
            val row = y * MODEL_SIZE
            for (x in radius until MODEL_SIZE - radius) {
                var keep = true
                for (dx in -radius..radius) {
                    if (!mask[row + x + dx]) {
                        keep = false
                        break
                    }
                }
                horizontal[row + x] = keep
            }
        }

        for (y in radius until MODEL_SIZE - radius) {
            val row = y * MODEL_SIZE
            for (x in 0 until MODEL_SIZE) {
                var keep = true
                for (dy in -radius..radius) {
                    if (!horizontal[(y + dy) * MODEL_SIZE + x]) {
                        keep = false
                        break
                    }
                }
                result[row + x] = keep
            }
        }
        return result
    }

    private fun iou(a: BoxLike, b: BoxLike): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.w * a.h + b.w * b.h - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun downloadModel(destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MasquageBullesManga/0.2")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Téléchargement du modèle impossible (HTTP ${connection.responseCode}).")
            }
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (temporary.length() < 1_000_000L) {
                throw IllegalStateException("Le fichier du modèle reçu est incomplet.")
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }
}
