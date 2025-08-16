package com.hello.http.http

import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object HttpResponse {
    fun okHtml(ins: InputStream) = object : Writer {
        override fun writeTo(out: OutputStream) {
            writeHead(out, 200, "OK", "text/html; charset=utf-8", -1, null)
            ins.use { it.copyTo(out) }
        }
    }
    fun okJson(body: String) = object : Writer {
        override fun writeTo(out: OutputStream) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            writeHead(out, 200, "OK", "application/json; charset=utf-8", bytes.size.toLong(), null)
            out.write(bytes)
        }
    }
    fun okStream(contentType: String, contentLength: Long, downloadName: String?) = object : Writer {
        override fun writeTo(out: OutputStream) {
            writeHead(out, 200, "OK", contentType, contentLength, downloadName)
        }
    }
    fun notFound() = text(404, "Not Found")
    fun badRequest(msg: String) = text(400, "Bad Request: $msg")
    fun forbidden(msg: String) = text(403, "Forbidden: $msg")

    private fun text(code: Int, msg: String) = object : Writer {
        override fun writeTo(out: OutputStream) {
            val b = msg.toByteArray(Charsets.UTF_8)
            writeHead(out, code, "ERR", "text/plain; charset=utf-8", b.size.toLong(), null)
            out.write(b)
        }
    }

    fun writeHead(
        out: OutputStream, code: Int, reason: String,
        contentType: String, contentLength: Long, downloadName: String?
    ) {
        fun w(s: String) = out.write(s.toByteArray(StandardCharsets.US_ASCII))
        w("HTTP/1.1 $code $reason\r\n")
        w("Server: QuickDrop/Android\r\n")
        w("Connection: close\r\n")
        w("Content-Type: $contentType\r\n")
        if (contentLength >= 0) w("Content-Length: $contentLength\r\n")
        if (downloadName != null) {
            val ascii = URLEncoder.encode(downloadName, "UTF-8")
            // 关键修改：从 attachment 改为 inline，让浏览器按 MIME 类型内联预览
            w("Content-Disposition: inline; filename*=UTF-8''$ascii\r\n")
        }
        // 对 HTML/JSON 明确禁用缓存，避免 /list 出现旧数据
        if (contentType.startsWith("application/json") || contentType.startsWith("text/html")) {
            w("Cache-Control: no-store\r\n")
            w("Pragma: no-cache\r\n")
        }
        w("\r\n")
    }

    // 定长 HTML：浏览器更稳（带 Content-Length）
    fun okHtmlString(body: String) = object : Writer {
        override fun writeTo(out: OutputStream) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            writeHead(out, 200, "OK", "text/html; charset=utf-8", bytes.size.toLong(), null)
            out.write(bytes)
        }
    }

    // 502 兜底（调试用）
    fun badGateway(msg: String) = object : Writer {
        override fun writeTo(out: OutputStream) {
            val b = ("Bad Gateway: " + msg).toByteArray(Charsets.UTF_8)
            writeHead(out, 502, "Bad Gateway", "text/plain; charset=utf-8", b.size.toLong(), null)
            out.write(b)
        }
    }

    interface Writer { fun writeTo(out: OutputStream) }
}
