package com.clawmate.app.task.data

import android.content.Context
import com.clawmate.app.R

/**
 * [BundledConfigReader] that reads the workflows bundled in `res/raw`. The Raw
 * resource id is resolved by name so a missing or renamed file degrades
 * gracefully (returns null) instead of crashing resource lookup.
 */
class AndroidBundledConfigReader(private val context: Context) : BundledConfigReader {

    override fun read(configFileName: String): String? {
        // R class exposes `R.raw.<lowercase file base>`. Rebuild the field name
        // defensively from the file name and call back into reflection only as a
        // fallback (the direct lookup below is the common path).
        val fieldName = configFileName
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9_]"), "_")
        val resId = rawResourceId(fieldName)
        if (resId <= 0) return null
        return try {
            context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            null
        }
    }

    private fun rawResourceId(name: String): Int = when (name) {
        "default_alipay_bill" -> R.raw.default_alipay_bill
        "default_bilibili_history" -> R.raw.default_bilibili_history
        "default_ctrip_order" -> R.raw.default_ctrip_order
        "default_dianping_order" -> R.raw.default_dianping_order
        "default_ele_order" -> R.raw.default_ele_order
        "default_meituan_order" -> R.raw.default_meituan_order
        "default_tencent_video_history" -> R.raw.default_tencent_video_history
        "default_weibo_hot_search" -> R.raw.default_weibo_hot_search
        "default_weixin_chat" -> R.raw.default_weixin_chat
        "default_weixin_today_chat" -> R.raw.default_weixin_today_chat
        "default_xiaohongshu_history" -> R.raw.default_xiaohongshu_history
        else -> 0
    }
}
