package local.meetingnotes.sdk

import org.json.JSONObject

/** Immutable for one Android process: Python module paths cannot be hot-swapped. */
data class MeetingConfiguration(
    val apiPort: Int = 18080,
    val summaryPort: Int = 18081,
    val threads: Int = 4,
    val storageDirectory: String = "meeting",
    val modelDirectory: String = "models",
    val modelIdleSeconds: Int = 120,
    val summaryIdleSeconds: Int = 60,
) {
    init {
        require(apiPort in 1024..65535 && summaryPort in 1024..65535 && apiPort != summaryPort)
        require(threads in 1..8)
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(storageDirectory))
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(modelDirectory))
        require(modelIdleSeconds in 10..3600 && summaryIdleSeconds in 10..3600)
    }
    val baseUrl get() = "http://127.0.0.1:$apiPort"
    fun toJson(): String = JSONObject().put("v", 1).put("api_port", apiPort).put("summary_port", summaryPort)
        .put("threads", threads).put("storage_directory", storageDirectory).put("model_directory", modelDirectory)
        .put("model_idle_seconds", modelIdleSeconds).put("summary_idle_seconds", summaryIdleSeconds).toString()

    companion object {
        fun fromJson(raw: String): MeetingConfiguration {
            val value = JSONObject(raw)
            require(value.optInt("v", 1) == 1) { "运行配置版本不兼容" }
            return MeetingConfiguration(value.optInt("api_port", 18080), value.optInt("summary_port", 18081),
                value.optInt("threads", 4), value.optString("storage_directory", "meeting"),
                value.optString("model_directory", "models"), value.optInt("model_idle_seconds", 120),
                value.optInt("summary_idle_seconds", 60))
        }
    }
}
