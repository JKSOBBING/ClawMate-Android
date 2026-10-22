package com.clawmate.app.chat.cloud

import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object ChatImageBounds {
    const val MAX_IMAGES = 4
    const val MAX_IMAGE_BYTES = 1024 * 1024
    const val MAX_DIMENSION = 1280
}

/** Plain text/tool requests keep their original serialization exactly. Images are explicit local data only. */
internal fun encodeChatCompletionsRequest(request: ChatCompletionsRequest, json: Json = WireJson.json): String {
    if (request.messages.none { it.imageUrls.isNotEmpty() }) return json.encodeToString(ChatCompletionsRequest.serializer(), request)
    require(request.messages.sumOf { it.imageUrls.size } <= ChatImageBounds.MAX_IMAGES) { "单次请求最多发送4张图片" }
    for (message in request.messages.filter { it.imageUrls.isNotEmpty() }) {
        require(message.role == "user") { "仅用户消息可以附加图片" }
        message.imageUrls.forEach(::validateChatImageDataUrl)
    }
    val original = json.encodeToJsonElement(ChatCompletionsRequest.serializer(), request).jsonObject
    val messages = original["messages"]!!.jsonArray.mapIndexed { i, value ->
        val message = request.messages[i]
        if (message.imageUrls.isEmpty()) value else JsonObject(value.jsonObject.toMutableMap().apply {
            put("content", buildJsonArray {
                message.content?.takeIf { it.isNotBlank() }?.let { text -> add(buildJsonObject { put("type", "text"); put("text", text) }) }
                message.imageUrls.forEach { url -> add(buildJsonObject {
                    put("type", "image_url"); putJsonObject("image_url") { put("url", url); put("detail", "auto") }
                }) }
            })
        })
    }
    return JsonObject(original.toMutableMap().apply { put("messages", JsonArray(messages)) }).toString()
}

internal fun validateChatImageDataUrl(value: String) {
    require(value.length <= (ChatImageBounds.MAX_IMAGE_BYTES + 2) / 3 * 4 + 64) { "图片编码超出大小限制" }
    val prefix = listOf("data:image/jpeg;base64,", "data:image/png;base64,").firstOrNull(value::startsWith)
        ?: throw IllegalArgumentException("图片必须是本机准备的 JPEG/PNG 数据，不能发送远程或本地路径")
    val encoded = value.substring(prefix.length)
    require(encoded.isNotEmpty() && encoded.length % 4 == 0 && Regex("[A-Za-z0-9+/]+={0,2}").matches(encoded)) { "图片编码无效" }
    val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("图片编码无效") }
    require(bytes.size in 1..ChatImageBounds.MAX_IMAGE_BYTES) { "图片数据超出大小限制" }
}
