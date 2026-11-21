package com.clawmate.shared

import android.graphics.Bitmap
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Only a synthetic private reference and a reserved invalid endpoint; never picks user photos. */
@RunWith(AndroidJUnit4::class)
class AvatarReferenceDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun savedReferenceReachesExplicitUploadConfirmationAndCancelPreservesVersions() {
        val context = instrumentation.targetContext
        val avatar = File(context.filesDir,"files/workflows/avatar")
        val state = File(avatar,"state.json")
        val settings = File(context.filesDir,"preference/shared_frontend_settings")
        val privacy = File(context.filesDir,"preference/privacy_consent")
        val originals = listOf(settings,privacy,state).associateWith { if(it.exists())it.readBytes()else null }
        val backup = File(context.cacheDir,"migration-avatar-backup")
        check(!backup.exists());check(backup.mkdirs())
        originals.entries.forEachIndexed { i,e -> e.value?.let { File(backup,"$i.bin").writeBytes(it) } }
        val photo = File(avatar,"assets/migration-avatar-${android.os.SystemClock.elapsedRealtime()}.jpg")
        val versionsBefore = File(avatar,"versions").list()?.toSet() ?: emptySet()
        try {
            photo.parentFile!!.mkdirs()
            val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            photo.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,85,it)) };bitmap.recycle()
            state.parentFile!!.mkdirs()
            state.writeText(JSONObject().put("current_version_id","").put("profile_snapshot_hash","").put("status","empty")
                .put("last_error_code","").put("last_error_message","").put("updated_at","")
                .put("reference_photo_path","assets/${photo.name}").toString())
            val fixtureState=state.readBytes()
            settings.parentFile!!.mkdirs()
            settings.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences>
                <string key="firstLaunchOnboardingVersion">2026-07-core-preview-usage-guide</string><bool key="androidCloudAccessGranted" value="true"/>
                <string key="avatarImageBaseUrl">https://avatar-fixture.invalid/images/generations</string>
                <string key="avatarImageApiKey">synthetic-key</string><string key="avatarTextToImageModel"></string>
                <string key="avatarImageToImageModel">synthetic-reference-model</string></preferences>""")
            privacy.writeText("""<?xml version="1.0" encoding="utf-8"?><preferences><string key="choice">accepted</string><string key="version">2026-10-26-android-v1</string></preferences>""")
            ActivityScenario.launch(SharedActivity::class.java).use {
                for (label in listOf("我的", "模型与智能体", "数字分身", "使用参考照片生成", "取消")) {
                    DevicePageControls.open(label)
                }
                Thread.sleep(700)
                assertArrayEquals(fixtureState,state.readBytes())
                assertEquals(versionsBefore,File(avatar,"versions").list()?.toSet() ?: emptySet<String>())
                instrumentation.sendStatus(2,Bundle().apply { putString("avatarPhotoConsent","Saved synthetic photo reached actual ArkTS upload confirmation; cancel preserved avatar state and versions") })
            }
        } finally {
            originals.forEach { (file,bytes) -> if(bytes==null)file.delete()else file.writeBytes(bytes) }
            photo.delete()
            backup.listFiles().orEmpty().forEach { it.delete() };check(backup.delete())
        }
    }
}
