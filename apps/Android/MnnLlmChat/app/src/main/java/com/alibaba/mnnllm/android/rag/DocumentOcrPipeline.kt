package com.alibaba.mnnllm.android.rag

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Offline OCR for imported image documents and selected scanned PDF pages.
 * All decoding and rendering operations are bounded before allocating bitmaps.
 */
interface DocumentOcrEngine {
    fun recognizeImage(file: File): List<LayoutBlock>
    fun recognizePdfPages(file: File, pageNumbers: List<Int>): List<LayoutBlock>
}

class DocumentOcrPipeline(
    private val limits: OcrLimits = OcrLimits(),
    private val recognize: (Bitmap) -> Text = MlKitChineseOcrRecognizer(limits)::recognize,
    private val nowNanos: () -> Long = System::nanoTime
) : DocumentOcrEngine {
    override fun recognizeImage(file: File): List<LayoutBlock> {
        require(file.isFile) { "Image private copy is missing" }
        require(file.length() in 1..limits.maxEncodedBytes) { "Image exceeds the configured size limit" }
        val startedAt = nowNanos()
        val bounds = decodeBounds(file)
        validateDimensions(bounds.first, bounds.second)
        val sampleSize = calculateSampleSize(bounds.first, bounds.second)
        val bitmap = decodeSampled(file, sampleSize)
        try {
            return recognizeBitmap(bitmap, pageNumber = null, startedAt = startedAt)
        } finally {
            bitmap.recycle()
        }
    }

    override fun recognizePdfPages(file: File, pageNumbers: List<Int>): List<LayoutBlock> {
        require(file.isFile) { "PDF private copy is missing" }
        require(pageNumbers.isNotEmpty()) { "OCR page list must not be empty" }
        require(pageNumbers.distinct().size == pageNumbers.size && pageNumbers.all { it > 0 }) {
            "OCR page numbers must be positive and unique"
        }
        require(pageNumbers.size <= limits.maxPdfPagesPerJob) { "PDF OCR page count exceeds the configured limit" }
        val startedAt = nowNanos()
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(descriptor).use { renderer ->
            require(pageNumbers.all { it <= renderer.pageCount }) { "PDF OCR page number is out of range" }
            val output = mutableListOf<LayoutBlock>()
            pageNumbers.sorted().forEach { pageNumber ->
                checkTimeBudget(startedAt)
                renderer.openPage(pageNumber - 1).use { page ->
                    val scale = renderScale(page.width, page.height)
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    validatePixelBudget(width, height)
                    val bitmap = allocateBitmap(width, height)
                    try {
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        output += recognizeBitmap(bitmap, pageNumber, startedAt)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            return output.mapIndexed { index, block -> block.copy(readingOrder = index) }
        }
    }

    private fun recognizeBitmap(bitmap: Bitmap, pageNumber: Int?, startedAt: Long): List<LayoutBlock> {
        checkTimeBudget(startedAt)
        val tiles = createTiles(bitmap)
        val output = mutableListOf<LayoutBlock>()
        try {
            tiles.forEach { tile ->
                checkTimeBudget(startedAt)
                val result = recognize(tile.bitmap)
                result.textBlocks.forEach { block ->
                    val value = normalizeText(block.text)
                    if (value.isNotBlank()) {
                        val box = block.boundingBox
                        output += LayoutBlock(
                            type = LayoutBlockType.OCR_TEXT,
                            text = value,
                            pageNumber = pageNumber,
                            bounds = box?.let {
                                LayoutBounds(
                                    left = tile.left + it.left.toFloat(),
                                    top = tile.top + it.top.toFloat(),
                                    right = tile.left + it.right.toFloat(),
                                    bottom = tile.top + it.bottom.toFloat()
                                )
                            },
                            readingOrder = output.size
                        )
                    }
                }
                require(output.sumOf { it.text.length } <= limits.maxRecognizedCharacters) {
                    "OCR text exceeds the configured character limit"
                }
            }
        } finally {
            tiles.forEach { if (it.bitmap !== bitmap) it.bitmap.recycle() }
        }
        checkTimeBudget(startedAt)
        return output
    }

    private fun createTiles(bitmap: Bitmap): List<OcrTile> {
        if (bitmap.width <= limits.maxTileEdge && bitmap.height <= limits.maxTileEdge) {
            return listOf(OcrTile(bitmap, 0f, 0f))
        }
        val columns = ceil(bitmap.width.toDouble() / limits.maxTileEdge).toInt()
        val rows = ceil(bitmap.height.toDouble() / limits.maxTileEdge).toInt()
        require(columns * rows <= limits.maxTiles) { "Image requires too many OCR tiles" }
        val output = mutableListOf<OcrTile>()
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val left = column * limits.maxTileEdge
                val top = row * limits.maxTileEdge
                val width = min(limits.maxTileEdge, bitmap.width - left)
                val height = min(limits.maxTileEdge, bitmap.height - top)
                output += OcrTile(Bitmap.createBitmap(bitmap, left, top, width, height), left.toFloat(), top.toFloat())
            }
        }
        return output
    }

    private fun decodeBounds(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported or damaged image" }
        return options.outWidth to options.outHeight
    }

    private fun decodeSampled(file: File, sampleSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            requireNotNull(BitmapFactory.decodeFile(file.absolutePath, options)) { "Image decoding failed" }
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException("Image decoding exceeded the memory budget", error)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (pixelCount(ceil(width.toDouble() / sample).toInt(), ceil(height.toDouble() / sample).toInt()) > limits.maxDecodedPixels) {
            sample = Math.multiplyExact(sample, 2)
        }
        return sample
    }

    private fun renderScale(width: Int, height: Int): Float {
        validateDimensions(width, height)
        val nativePixels = pixelCount(width, height).toDouble()
        return min(limits.pdfRenderScale, sqrt(limits.maxDecodedPixels / nativePixels)).coerceAtMost(1f)
    }

    private fun allocateBitmap(width: Int, height: Int): Bitmap = try {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    } catch (error: OutOfMemoryError) {
        throw IllegalStateException("PDF page rendering exceeded the memory budget", error)
    }

    private fun validateDimensions(width: Int, height: Int) {
        require(width in 1..limits.maxSourceEdge && height in 1..limits.maxSourceEdge) {
            "Image dimensions exceed the configured limit"
        }
        require(pixelCount(width, height) <= limits.maxSourcePixels) {
            "Image pixel count exceeds the configured limit"
        }
    }

    private fun validatePixelBudget(width: Int, height: Int) {
        require(pixelCount(width, height) <= limits.maxDecodedPixels) { "Decoded image exceeds the pixel budget" }
        require(pixelCount(width, height) * 4L <= limits.maxBitmapBytes) { "Decoded image exceeds the memory budget" }
    }

    private fun pixelCount(width: Int, height: Int): Long = Math.multiplyExact(width.toLong(), height.toLong())

    private fun checkTimeBudget(startedAt: Long) {
        val elapsed = nowNanos() - startedAt
        require(elapsed >= 0L && elapsed <= limits.maxProcessingNanos) { "OCR exceeded the configured time limit" }
    }

    private data class OcrTile(val bitmap: Bitmap, val left: Float, val top: Float)
}

class MlKitChineseOcrRecognizer(private val limits: OcrLimits) : AutoCloseable {
    private val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    fun recognize(bitmap: Bitmap): Text = Tasks.await(
        client.process(InputImage.fromBitmap(bitmap, 0)),
        limits.perTileTimeoutSeconds,
        TimeUnit.SECONDS
    )

    override fun close() = client.close()
}

data class OcrLimits(
    val maxEncodedBytes: Long = 64L * 1024L * 1024L,
    val maxSourceEdge: Int = 40_000,
    val maxSourcePixels: Long = 160_000_000L,
    val maxDecodedPixels: Long = 16_000_000L,
    val maxBitmapBytes: Long = 64L * 1024L * 1024L,
    val maxTileEdge: Int = 2_048,
    val maxTiles: Int = 64,
    val maxPdfPagesPerJob: Int = 200,
    val pdfRenderScale: Float = 1f,
    val maxRecognizedCharacters: Int = 2_000_000,
    val perTileTimeoutSeconds: Long = 30L,
    val maxProcessingNanos: Long = 10L * 60L * 1_000_000_000L
) {
    init {
        require(maxEncodedBytes > 0L)
        require(maxSourceEdge > 0)
        require(maxSourcePixels > 0L)
        require(maxDecodedPixels > 0L && maxDecodedPixels <= maxSourcePixels)
        require(maxBitmapBytes >= maxDecodedPixels * 4L)
        require(maxTileEdge > 0 && maxTiles > 0)
        require(maxPdfPagesPerJob > 0)
        require(pdfRenderScale > 0f)
        require(maxRecognizedCharacters > 0)
        require(perTileTimeoutSeconds > 0L)
        require(maxProcessingNanos > 0L)
    }
}
