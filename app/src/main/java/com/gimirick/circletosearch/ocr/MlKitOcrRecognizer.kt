package com.gimirick.circletosearch.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.gimirick.circletosearch.ui.components.TextNode
import com.gimirick.circletosearch.ui.components.Word
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor

/**
 * High-accuracy on-device OCR recognizer powered by Google ML Kit.
 *
 * Supports both Devanagari (Hindi, Marathi, Sanskrit, Nepali, Bhojpuri) and Latin (English)
 * scripts in the exact same frame simultaneously with hardware acceleration.
 */
object MlKitOcrRecognizer {
    private const val TAG = "MlKitOcrRecognizer"

    @Volatile
    private var recognizer: TextRecognizer? = null

    private fun getRecognizer(): TextRecognizer {
        return recognizer ?: synchronized(this) {
            recognizer ?: TextRecognition.getClient(
                DevanagariTextRecognizerOptions.Builder().build()
            ).also { recognizer = it }
        }
    }

    suspend fun warmUp() = withContext(Dispatchers.Default) {
        getRecognizer()
    }

    suspend fun recognize(bitmap: Bitmap): List<TextNode> = withContext(Dispatchers.Default) {
        require(!bitmap.isRecycled) { "Cannot run OCR on recycled bitmap" }
        require(bitmap.width > 0 && bitmap.height > 0) { "Cannot run OCR on empty bitmap" }

        val image = InputImage.fromBitmap(bitmap, 0)
        val visionText = getRecognizer().process(image).await()
        mapVisionTextToNodes(
            visionText = visionText,
            sourceWidth = bitmap.width,
            sourceHeight = bitmap.height,
        )
    }

    suspend fun recognizeRegion(
        bitmap: Bitmap,
        sourceRegion: RectF,
    ): List<TextNode> = withContext(Dispatchers.Default) {
        require(!bitmap.isRecycled) { "Cannot run OCR on recycled bitmap" }
        if (!sourceRegion.left.isFinite() || !sourceRegion.top.isFinite() ||
            !sourceRegion.right.isFinite() || !sourceRegion.bottom.isFinite()
        ) {
            return@withContext emptyList()
        }

        val left = floor(sourceRegion.left.toDouble()).toInt().coerceIn(0, bitmap.width)
        val top = floor(sourceRegion.top.toDouble()).toInt().coerceIn(0, bitmap.height)
        val right = ceil(sourceRegion.right.toDouble()).toInt().coerceIn(0, bitmap.width)
        val bottom = ceil(sourceRegion.bottom.toDouble()).toInt().coerceIn(0, bitmap.height)

        val width = right - left
        val height = bottom - top
        if (width < 4 || height < 4) return@withContext emptyList()

        val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)
        try {
            val image = InputImage.fromBitmap(cropped, 0)
            val visionText = getRecognizer().process(image).await()
            mapVisionTextToNodes(
                visionText = visionText,
                sourceWidth = bitmap.width,
                sourceHeight = bitmap.height,
                offsetX = left.toFloat(),
                offsetY = top.toFloat(),
            )
        } finally {
            cropped.recycle()
        }
    }

    fun release() {
        synchronized(this) {
            try {
                recognizer?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing ML Kit recognizer", e)
            } finally {
                recognizer = null
            }
        }
    }

    internal fun mapVisionTextToNodes(
        visionText: Text,
        sourceWidth: Int,
        sourceHeight: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): List<TextNode> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return emptyList()
        val nodes = mutableListOf<TextNode>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val rawText = line.text
                val trimStart = rawText.indexOfFirst { !it.isWhitespace() }
                if (trimStart < 0) continue
                val trimEndExclusive = rawText.indexOfLast { !it.isWhitespace() } + 1
                val fullText = rawText.substring(trimStart, trimEndExclusive)
                if (fullText.isEmpty()) continue

                val lineBox = line.boundingBox ?: continue
                val left = (lineBox.left + offsetX).toInt().coerceIn(0, sourceWidth)
                val top = (lineBox.top + offsetY).toInt().coerceIn(0, sourceHeight)
                val right = (lineBox.right + offsetX).toInt().coerceIn(0, sourceWidth)
                val bottom = (lineBox.bottom + offsetY).toInt().coerceIn(0, sourceHeight)
                if (right <= left || bottom <= top) continue

                val lineBounds = Rect(left, top, right, bottom)

                val elements = line.elements
                val words = mutableListOf<Word>()
                var searchFrom = trimStart

                if (elements.isNotEmpty()) {
                    elements.forEachIndexed { index, element ->
                        val elemText = element.text.trim()
                        if (elemText.isNotEmpty()) {
                            val foundIndex = rawText.indexOf(elemText, searchFrom)
                            val wordStart = if (foundIndex >= 0) {
                                searchFrom = foundIndex + elemText.length
                                (foundIndex - trimStart).coerceAtLeast(0)
                            } else {
                                val fallbackStart = (searchFrom - trimStart).coerceIn(0, fullText.length)
                                searchFrom = (searchFrom + elemText.length).coerceAtMost(rawText.length)
                                fallbackStart
                            }
                            val wordEnd = (wordStart + elemText.length).coerceAtMost(fullText.length)
                            val elemBox = element.boundingBox ?: lineBox
                            val elemLeft = (elemBox.left + offsetX).coerceIn(0f, sourceWidth.toFloat())
                            val elemTop = (elemBox.top + offsetY).coerceIn(0f, sourceHeight.toFloat())
                            val elemRight = (elemBox.right + offsetX).coerceIn(0f, sourceWidth.toFloat())
                            val elemBottom = (elemBox.bottom + offsetY).coerceIn(0f, sourceHeight.toFloat())

                            words.add(
                                Word(
                                    text = elemText,
                                    index = index,
                                    startIndex = wordStart,
                                    endIndex = wordEnd,
                                    bounds = RectF(elemLeft, elemTop, elemRight, elemBottom),
                                )
                            )
                        }
                    }
                }

                val finalWords = if (words.isNotEmpty()) {
                    words
                } else {
                    val matches = Regex("\\S+").findAll(fullText).toList()
                    val codePointCount = fullText.codePointCount(0, fullText.length).coerceAtLeast(1)
                    matches.mapIndexed { index, match ->
                        val startFraction = fullText.codePointCount(0, match.range.first).toFloat() / codePointCount
                        val endFraction = fullText.codePointCount(0, match.range.last + 1).toFloat() / codePointCount
                        val wLeft = left + ((right - left) * startFraction)
                        val wRight = left + ((right - left) * endFraction)
                        Word(
                            text = match.value,
                            index = index,
                            startIndex = match.range.first,
                            endIndex = match.range.last + 1,
                            bounds = RectF(wLeft, top.toFloat(), wRight, bottom.toFloat()),
                        )
                    }
                }

                if (finalWords.isNotEmpty()) {
                    nodes.add(
                        TextNode(
                            id = buildString(fullText.length + 48) {
                                append(fullText)
                                append('@')
                                append(lineBounds.left)
                                append(',')
                                append(lineBounds.top)
                                append(',')
                                append(lineBounds.right)
                                append(',')
                                append(lineBounds.bottom)
                            },
                            fullText = fullText,
                            bounds = lineBounds,
                            words = finalWords,
                        )
                    )
                }
            }
        }
        return nodes
    }
}
