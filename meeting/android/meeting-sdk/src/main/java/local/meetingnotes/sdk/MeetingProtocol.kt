package local.meetingnotes.sdk

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Versioned, bounded commands suitable for a host's JSON bridge. */
enum class MeetingOperation(val method: String, val route: String, val queryKeys: Set<String> = emptySet()) {
    HEALTH("GET", "health"), MODELS_STATUS("GET", "models/status"), MODELS_DOWNLOAD("POST", "models/download"),
    RUNTIME_STATUS("GET", "runtime/status"), RELEASE_MODELS("POST", "runtime/release"),
    HISTORY("GET", "history", setOf("view", "q", "status", "source", "since", "until", "sort", "page", "page_size")),
    CREATE_DRAFT("POST", "inbox"), CREATE_MEETING("POST", "meetings"), MEETING("GET", "meetings/{id}"),
    UPDATE_MEETING("PATCH", "meetings/{id}"), PROMOTE("POST", "meetings/{id}/promote"),
    HISTORY_BATCH("POST", "history/batch"), DOCUMENT("GET", "meetings/{id}/document"),
    SAVE_DOCUMENT("PUT", "meetings/{id}/document"), VERSIONS("GET", "meetings/{id}/versions"),
    RESTORE_VERSION("POST", "meetings/{id}/versions/restore"), AUDIO_VERSIONS("GET", "meetings/{id}/audio/versions"),
    RECOVER_AUDIO("POST", "meetings/{id}/audio/{audio_id}/recover"), PROCESS("POST", "meetings/{id}/process"),
    SUMMARY("POST", "meetings/{id}/summary"), TASKS("GET", "tasks", setOf("meeting_id")),
    TASK("GET", "tasks/{id}"), CANCEL_TASK("POST", "tasks/{id}/cancel"), RETRY_TASK("POST", "tasks/{id}/retry"),
    TEMPLATES("GET", "templates"), CREATE_TEMPLATE("POST", "templates"), SAVE_TEMPLATE("PUT", "templates/{id}"),
    DELETE_TEMPLATE("DELETE", "templates/{id}"), SETTINGS("GET", "meetings/{id}/settings"),
    APPLY_TEMPLATE("PUT", "meetings/{id}/settings"), REFINEMENT("GET", "refinement"), SAVE_REFINEMENT("PUT", "refinement"),
    HANDOFF("POST", "meetings/{id}/handoff");
}

internal object MeetingProtocol {
    fun endpoint(value: String): HttpUrl {
        val url = value.toHttpUrl()
        require(url.scheme == "http" && url.host == "127.0.0.1" && url.username.isEmpty() && url.password.isEmpty()) {
            "会议接口必须是手机环回 HTTP 地址"
        }
        require(url.encodedPath == "/" && url.query == null && url.fragment == null)
        return url
    }

    fun id(value: String): String {
        require(Regex("[0-9a-f]{32}").matches(value)) { "无效的会议、音频或任务 ID" }
        return value
    }

    fun url(base: HttpUrl, operation: MeetingOperation, args: JSONObject): HttpUrl {
        var route = operation.route
        if (route.contains("{id}")) {
            val value = args.getString("id")
            if (route.startsWith("templates/")) require(Regex("[A-Za-z0-9_-]{1,80}").matches(value))
            else id(value)
            route = route.replace("{id}", value)
        }
        if (route.contains("{audio_id}")) route = route.replace("{audio_id}", id(args.getString("audio_id")))
        val builder = base.newBuilder().addPathSegments("api/v1/$route")
        args.optJSONObject("query")?.let { query ->
            for (key in query.keys()) {
                require(key in operation.queryKeys) { "不支持的查询参数：$key" }
                builder.addQueryParameter(key, query.getString(key))
            }
        }
        return builder.build()
    }

    fun streamUrl(base: HttpUrl, meetingId: String, compare: Boolean = false): String =
        base.newBuilder().addPathSegments("api/v1/meetings/${id(meetingId)}/stream")
            .addQueryParameter("mode", "sensevoice_dual").addQueryParameter("compare_qwen", compare.toString())
            .build().toString().replaceFirst("http://", "ws://")

    fun event(raw: String): JSONObject {
        require(raw.length <= 4 * 1024 * 1024) { "会议事件过大" }
        return JSONObject(raw).also { require(it.optString("type").isNotEmpty()) { "事件缺少类型" } }
    }
}
