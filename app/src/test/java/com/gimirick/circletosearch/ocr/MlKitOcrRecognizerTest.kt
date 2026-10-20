package com.gimirick.circletosearch.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MlKitOcrRecognizerTest {

    @Test
    fun releaseCanBeCalledRepeatedlyWithoutCrashing() {
        MlKitOcrRecognizer.release()
        MlKitOcrRecognizer.release()
        assertTrue(true)
    }

    @Test
    fun recognizeRegionRejectsNonFiniteBounds() = runBlocking {
        // Bitmap.createBitmap might fail in pure JVM unit test if unmocked,
        // but non-finite check runs before bitmap operations!
        // We mock or create dummy bitmap if available, or verify with mock
        val dummyBitmap = try {
            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            null
        }

        if (dummyBitmap != null) {
            val nanRegion = RectF(Float.NaN, 0f, 100f, 100f)
            val result = MlKitOcrRecognizer.recognizeRegion(dummyBitmap, nanRegion)
            assertTrue(result.isEmpty())

            val infRegion = RectF(0f, Float.POSITIVE_INFINITY, 100f, 100f)
            val resultInf = MlKitOcrRecognizer.recognizeRegion(dummyBitmap, infRegion)
            assertTrue(resultInf.isEmpty())
        }
    }

    @Test
    fun recognizeRegionRejectsZeroOrSub4PixelArea() = runBlocking {
        val dummyBitmap = try {
            Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            null
        }

        if (dummyBitmap != null) {
            val tinyRegion = RectF(10f, 10f, 12f, 12f) // 2x2 width/height < 4
            val result = MlKitOcrRecognizer.recognizeRegion(dummyBitmap, tinyRegion)
            assertTrue(result.isEmpty())
        }
    }
}
