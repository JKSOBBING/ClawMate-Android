package com.clawmate.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clawmate.app.chat.cloud.ChatImageBounds
import com.clawmate.app.image.AndroidChatImagePreparer
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatImagePreparationDeviceTest {
    @Test fun downsizingOrientationAndMetadataRemovalUseRealAndroidBitmaps() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = Files.createTempDirectory(context.cacheDir.toPath(), "chat-image-test-").toFile()
        val source = Bitmap.createBitmap(2561, 1200, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.GREEN)
            val file = File(base, "fixture.jpg")
            file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setLatLong(12.34, 56.78)
                setAttribute(ExifInterface.TAG_MAKE, "DeviceTestFixture")
                saveAttributes()
            }
            val prepared = AndroidChatImagePreparer(context).prepare(Uri.fromFile(file).toString())
            assertTrue(prepared.width < prepared.height)
            assertTrue(maxOf(prepared.width, prepared.height) <= ChatImageBounds.MAX_DIMENSION)
            assertTrue(prepared.byteCount <= ChatImageBounds.MAX_IMAGE_BYTES)
            assertFalse(prepared.toString().contains("base64"))
            val bytes = Base64.getDecoder().decode(prepared.dataUrl.substringAfter(','))
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(bitmap)
            assertEquals(prepared.width, bitmap.width)
            assertEquals(prepared.height, bitmap.height)
            bitmap.recycle()
            val metadata = ExifInterface(ByteArrayInputStream(bytes))
            assertNull(metadata.latLong)
            assertNull(metadata.getAttribute(ExifInterface.TAG_MAKE))
        } finally { source.recycle(); base.deleteRecursively() }
    }

    @Test fun transparentBackgroundBecomesWhiteAndHorizontalFlipIsApplied() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = Files.createTempDirectory(context.cacheDir.toPath(), "chat-image-flip-test-").toFile()
        val source = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
        try {
            val transparent = File(base, "transparent.png")
            source.eraseColor(Color.TRANSPARENT)
            transparent.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val white = AndroidChatImagePreparer(context).prepare(Uri.fromFile(transparent).toString())
            val whiteBytes = Base64.getDecoder().decode(white.dataUrl.substringAfter(','))
            BitmapFactory.decodeByteArray(whiteBytes, 0, whiteBytes.size).also { result ->
                assertTrue(Color.red(result.getPixel(30, 30)) > 245); result.recycle()
            }
            Canvas(source).apply {
                drawColor(Color.BLUE)
                drawRect(0f, 0f, 60f, 60f, Paint().apply { color = Color.RED })
            }
            val flip = File(base, "flip.jpg")
            flip.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            ExifInterface(flip).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "2"); saveAttributes() }
            val prepared = AndroidChatImagePreparer(context).prepare(Uri.fromFile(flip).toString())
            val bytes = Base64.getDecoder().decode(prepared.dataUrl.substringAfter(','))
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size).also { result ->
                assertTrue(Color.blue(result.getPixel(20, 30)) > Color.red(result.getPixel(20, 30)))
                assertTrue(Color.red(result.getPixel(100, 30)) > Color.blue(result.getPixel(100, 30)))
                result.recycle()
            }
        } finally { source.recycle(); base.deleteRecursively() }
    }

    @Test fun networkAndExternalFilePathsAreNotOpened() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preparer = AndroidChatImagePreparer(context)
        assertTrue(runCatching { preparer.prepare("https://example.invalid/photo.jpg") }.isFailure)
        assertTrue(runCatching { preparer.prepare("file:///sdcard/DCIM/private-photo.jpg") }.isFailure)
    }
}
