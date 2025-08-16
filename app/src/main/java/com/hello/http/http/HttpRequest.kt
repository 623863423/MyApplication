package com.hello.http.http

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val queryParams: Map<String, String>
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]
    fun param(name: String): String? = queryParams[name]

    companion object {
        private val ASCII = StandardCharsets.US_ASCII // 避免 Charset.forName 的查找开销

        fun parse(ins: InputStream): HttpRequest? {
            // 读取直到 \r\n\r\n
            val head = ByteArray(16 * 1024)
            var len = 0
            var matched = 0
            while (true) {
                val b = ins.read()
                if (b == -1) return null
                if (len >= head.size) return null
                head[len++] = b.toByte()
                // 匹配 \r\n\r\n
                when (matched) {
                    0, 2 -> if (b == '\r'.code) matched++ else matched = 0
                    1, 3 -> if (b == '\n'.code) matched++ else matched = 0
                }
                if (matched == 4) break
            }
            val headerText = String(head, 0, len, ASCII)
            val lines = headerText.split("\r\n")
            if (lines.isEmpty()) return null
            val start = lines[0].split(' ')
            if (start.size < 2) return null
            val method = start[0].uppercase(Locale.US)
            val rawPath = start[1]
            val headers = LinkedHashMap<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) break
                val p = line.indexOf(':')
                if (p > 0) {
                    val k = line.substring(0, p).trim().lowercase(Locale.US)
                    val v = line.substring(p + 1).trim()
                    headers[k] = if (headers.containsKey(k)) headers[k] + "," + v else v
                }
            }
            val qIdx = rawPath.indexOf('?')
            val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
            val query = if (qIdx >= 0) rawPath.substring(qIdx + 1) else ""
            val params = LinkedHashMap<String, String>()
            if (query.isNotEmpty()) {
                query.split('&').forEach { kv ->
                    if (kv.isEmpty()) return@forEach
                    val eq = kv.indexOf('=')
                    val k = if (eq >= 0) kv.substring(0, eq) else kv
                    val v = if (eq >= 0) kv.substring(eq + 1) else ""
                    params[urlDecode(k)] = urlDecode(v)
                }
            }
            return HttpRequest(method, path, headers, params)
        }

        private fun urlDecode(s: String): String =
            try { java.net.URLDecoder.decode(s, "UTF-8") } catch (_: Throwable) { s }
    }
}
