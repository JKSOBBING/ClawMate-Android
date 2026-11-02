package com.clawmate.shared

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled activity-result contract, not a claim of manual OEM picker acceptance. */
@RunWith(AndroidJUnit4::class)
class PhotoPickerDeviceTest {
    @Test fun preservesContentGrantUriBoundsSelectionAndCancels() {
        ActivityScenario.launch(SharedActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val callback = SharedActivity::class.java.getDeclaredMethod("onActivityResult",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
                val id = activity.startPhotoPicker(1)
                val uri = Uri.parse("content://synthetic.fixture/document/image")
                val data = Intent().apply {
                    clipData = ClipData.newRawUri("synthetic", uri).apply {
                        addItem(ClipData.Item(Uri.parse("content://synthetic.fixture/document/second")))
                    }
                }
                callback.invoke(activity, 7202, Activity.RESULT_OK, data)
                val result = JSONObject(activity.pollPhotoPicker(id))
                assertEquals("done", result.getString("status"))
                assertEquals(1, result.getJSONArray("uris").length())
                assertEquals(uri.toString(), result.getJSONArray("uris").getString(0))
                activity.cancelPhotoPicker(id)
                callback.invoke(activity, 7202, Activity.RESULT_OK, data)
                assertEquals("cancelled", JSONObject(activity.pollPhotoPicker(id)).getString("status"))
                assertEquals(0, JSONObject(activity.pollPhotoPicker(id)).getJSONArray("uris").length())
                activity.finishActivity(7202)
            }
        }
    }
}
