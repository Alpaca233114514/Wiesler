package io.wiesler.android

data class RequestLog(val time: String, val source: String, val status: Int?, val error: String? = null) {
    val failed: Boolean get() = error != null || (status != null && status >= 400)
    val result: String get() = when {
        failed -> "失败"
        status in 200..299 -> "成功"
        status in 300..399 -> "重定向"
        status in 100..199 -> "信息响应"
        else -> "未知"
    }
    fun text(): String = "$time · $source\n${status?.let { "HTTP $it" } ?: "未取得状态码"} · $result${error?.let { " · $it" } ?: ""}"
}
