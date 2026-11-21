package com.clawmate.shared

import android.graphics.Rect
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Locate a rendered ArkUI row by its own text, including on devices with sparse accessibility nodes. */
internal object DevicePageControls {
    fun open(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        val hostPackage = instrumentation.targetContext.packageName
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        fun checkHost() {
            check(ui.rootInActiveWindow?.packageName?.toString() == hostPackage) {
                "Test navigation lost the ClawMate window"
            }
        }
        fun shell(command: String) {
            checkHost()
            ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes() }
        }
        try {
            repeat(10) { attempt ->
                checkHost()
                val image = ui.takeScreenshot() ?: error("Missing test window")
                try {
                    val lines = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)), 15, TimeUnit.SECONDS)
                        .textBlocks.flatMap { it.lines }
                    val boxes = mutableListOf<Rect>()
                    for (line in lines) {
                        if (line.text.filterNot(Char::isWhitespace) == label) line.boundingBox?.let(boxes::add)
                        for (start in line.elements.indices) {
                            val bounds = Rect()
                            var text = ""
                            for (part in line.elements.drop(start)) {
                                text += part.text.filterNot(Char::isWhitespace)
                                part.boundingBox?.let(bounds::union)
                                if (text == label && !bounds.isEmpty) boxes.add(Rect(bounds))
                                if (!label.startsWith(text) || text == label) break
                            }
                        }
                    }
                    val bounds = boxes.firstOrNull()
                    if (bounds != null) {
                        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                        Thread.sleep(400)
                        return
                    }
                    if (attempt >= 2) {
                        val x = image.width / 2
                        shell("input swipe $x ${image.height * 3 / 4} $x ${image.height * 2 / 5} 350")
                    }
                } finally { image.recycle() }
                Thread.sleep(380)
            }
            error("Rendered page control missing: $label")
        } finally { recognizer.close() }
    }
}
