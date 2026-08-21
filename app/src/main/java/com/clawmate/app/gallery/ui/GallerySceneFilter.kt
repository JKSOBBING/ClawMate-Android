package com.clawmate.app.gallery.ui

import com.clawmate.app.gallery.model.GalleryPhotoItem

/** Pure scene matching used by the Collection first-viewport tabs. */
internal fun gallerySceneMatches(item: GalleryPhotoItem, scene: String): Boolean {
    if (scene == "全部") return true
    val text = buildString {
        append(item.category).append(' ')
        append(item.title).append(' ')
        append(item.summary).append(' ')
        append(item.tags.joinToString(" "))
    }
    val keywords = when (scene) {
        "购物" -> listOf("购物", "商品", "淘宝", "京东", "订单")
        "外卖" -> listOf("外卖", "餐", "美食", "配送", "饿了么", "美团")
        "娱乐" -> listOf("娱乐", "电影", "音乐", "游戏", "视频")
        "聊天" -> listOf("聊天", "微信", "消息", "对话", "社交")
        "生活" -> listOf("生活", "家", "健康", "天气", "日常")
        "社交" -> listOf("社交", "朋友", "聚会", "活动")
        "差旅" -> listOf("差旅", "旅行", "旅游", "酒店", "机票", "出行")
        else -> emptyList()
    }
    return keywords.any(text::contains)
}
