package com.hello.http

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.hello.http.http.HttpRequest
import com.hello.http.http.HttpResponse
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 轻量本地 HTTP 服务器（/exit 路径先关监听再回调 stopSelf）
 */
class TinyHttpServer(
    private val context: Context,
    private val rootUri: Uri,
    private val port: Int,
    private val pin: String,
    private val onExit: (() -> Unit)? = null,
    private val onReady: (() -> Unit)? = null
) : Closeable {

    @Volatile private var closed = false
    private val pool = Executors.newFixedThreadPool(4)
    private lateinit var serverSocket: ServerSocket
    private val resolver: ContentResolver get() = context.contentResolver

    /** 内存文本（≤2500 字，不落盘） */
    private data class TextMsg(val text: String, val ts: Long)
    private val texts = java.util.Collections.synchronizedList(mutableListOf<TextMsg>())
    private val maxTextItems = 200

    /** 启动监听（阻塞调用线程） */
    fun start() {
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }

        onReady?.let { cb ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { cb.invoke() }
        }

        while (!closed) {
            try {
                val sock = serverSocket.accept()
                pool.execute { handle(sock) }
            } catch (_: Throwable) {
                if (closed) break
            }
        }
    }

    /** 彻底关闭：释放端口 + 线程池，并给内核稍长释放间隔 */
    override fun close() {
        closed = true
        runCatching { serverSocket.close() }
        runCatching { pool.shutdownNow() }
        try { Thread.sleep(300) } catch (_: Throwable) {}
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 20_000
        sock.tcpNoDelay = true
        val ins = BufferedInputStream(sock.getInputStream(), 64 * 1024)
        val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
        try {
            val req = HttpRequest.parse(ins) ?: run {
                HttpResponse.badRequest("Invalid Request").writeTo(out); return
            }
            when {
                req.path == "/" && req.method.equals("GET", true) -> serveIndex(out)
                req.path == "/list" && req.method.equals("GET", true) -> handleList(out)
                req.path == "/text" && req.method.equals("POST", true) -> handleText(req, ins, out)
                req.path == "/upload" && req.method.equals("POST", true) -> handleUpload(req, ins, out)
                req.path.startsWith("/files/") && req.method.equals("GET", true) -> handleFileGet(req, out)
                req.path == "/clear" && req.method.equals("POST", true) -> handleClear(out)
                req.path == "/exit" && (req.method.equals("POST", true) || req.method.equals("GET", true)) -> {
                    // 先答复 200，让前端尽快更新 UI
                    HttpResponse.okJson("""{"ok":true}""").writeTo(out)
                    runCatching { out.flush() }
                    // 然后立即关闭监听与线程（释放端口），最后回调 Service.stopSelf()
                    runCatching { close() }
                    runCatching { onExit?.invoke() }
                }
                else -> HttpResponse.notFound().writeTo(out)
            }
        } catch (e: Throwable) {
            HttpResponse.badGateway(e.toString()).writeTo(out)
        } finally {
            runCatching { out.flush() }
            runCatching { sock.close() }
        }
    }

    private fun serveIndex(out: java.io.OutputStream) {
        val html = runCatching { context.assets.open("quickdrop.html") }.getOrNull()
        if (html != null) HttpResponse.okHtml(html).writeTo(out)
        else HttpResponse.notFound().writeTo(out)
    }

    // ===== 路由实现 =====

    private fun handleList(out: java.io.OutputStream) {
        val list = mutableListOf<Pair<Long, String>>()

        // 文本消息
        val tSnapshot = ArrayList<TextMsg>()
        synchronized(texts) { tSnapshot.addAll(texts) }
        for (t in tSnapshot) {
            val json = """{"kind":"text","text":${jsonStr(t.text)},"ts":${t.ts}}"""
            list += t.ts to json
        }

        // 目录文件
        val dir = root()
        for (f in dir.listFiles()) {
            if (!f.isFile) continue
            val name = f.name ?: continue
            val ts = f.lastModified()
            val size = f.length()
            val url = "/files/" + urlEncode(name)
            val json = """{"kind":"file","name":${jsonStr(name)},"url":${jsonStr(url)},"size":$size,"ts":$ts}"""
            list += ts to json
        }

        list.sortBy { it.first }
        val body = buildString(64 + list.size * 64) {
            append("{\"items\":[")
            list.forEachIndexed { i, p -> if (i > 0) append(','); append(p.second) }
            append("]}")
        }
        HttpResponse.okJson(body).writeTo(out)
    }

    /** /text：≤2500 字入内存队列（不落盘） */
    private fun handleText(req: HttpRequest, ins: BufferedInputStream, out: java.io.OutputStream) {
        val cl = req.header("content-length")?.toLongOrNull() ?: -1L
        val maxGuard = 64 * 1024
        if (cl < 0 || cl > maxGuard) {
            val baos = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                baos.write(buf, 0, n); total += n
                if (total > maxGuard) break
            }
            val text = baos.toString("UTF-8")
            if (text.length > 2500) { HttpResponse.badRequest("text too long").writeTo(out); return }
            addText(text)
            HttpResponse.okJson("""{"ok":true}""").writeTo(out)
            return
        }
        val buf = ByteArray(cl.toInt())
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off); if (n <= 0) break; off += n
        }
        val text = String(buf, 0, off, Charsets.UTF_8)
        if (text.length > 2500) { HttpResponse.badRequest("text too long").writeTo(out); return }
        addText(text)
        HttpResponse.okJson("""{"ok":true}""").writeTo(out)
    }

    private fun addText(text: String) {
        val now = System.currentTimeMillis()
        synchronized(texts) {
            texts.add(TextMsg(text, now))
            if (texts.size > maxTextItems) {
                val cut = texts.size - maxTextItems
                repeat(cut) { texts.removeAt(0) }
            }
        }
    }

    /** /upload：原样保存（?name=） */
    private fun handleUpload(req: HttpRequest, ins: BufferedInputStream, out: java.io.OutputStream) {
        val rawName = req.param("name") ?: "upload.bin"
        val safe = U.safeName(rawName)
        val dir = root()
        val finalName = uniqueName(dir, safe)
        val mime = U.guessMime(finalName)
        val file = dir.createFile(mime, stripExtForCreate(finalName)) ?: run {
            HttpResponse.badGateway("create failed").writeTo(out); return
        }

        val declared = req.header("x-file-size")?.toLongOrNull()
            ?: req.header("content-length")?.toLongOrNull()
            ?: -1L

        resolver.openOutputStream(file.uri, "w")?.use { os ->
            val bos = BufferedOutputStream(os, 64 * 1024)
            if (declared >= 0) copyExactly(ins, bos, declared)
            else {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n <= 0) break
                    bos.write(buf, 0, n)
                }
            }
            bos.flush()
        }
        HttpResponse.okJson("""{"ok":true,"name":${jsonStr(finalName)}}""").writeTo(out)
    }

    /** /files/<name>：inline 读取原文件 */
    private fun handleFileGet(req: HttpRequest, out: java.io.OutputStream) {
        val enc = req.path.removePrefix("/files/")
        val name = urlDecode(enc)
        val f = findFile(root(), name) ?: run {
            HttpResponse.notFound().writeTo(out); return
        }
        val ctype = f.type ?: U.guessMime(f.name ?: name)
        val len = f.length()
        HttpResponse.writeHead(out, 200, "OK", ctype, len, null)
        resolver.openInputStream(f.uri)?.use { it.copyTo(out) }
    }

    /** /clear：清除内存文本与目录下文件 */
    private fun handleClear(out: java.io.OutputStream) {
        val dir = root()
        dir.listFiles().forEach { if (it.isFile) runCatching { it.delete() } }
        synchronized(texts) { texts.clear() }
        HttpResponse.okJson("""{"ok":true}""").writeTo(out)
    }

    // ===== 工具区 =====

    private fun root(): DocumentFile =
        DocumentFile.fromTreeUri(context, rootUri)
            ?: throw IllegalStateException("invalid root uri")

    private fun uniqueName(dir: DocumentFile, name: String): String {
        if (findFile(dir, name) == null) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val nn = "$base($i)$ext"
            if (findFile(dir, nn) == null) return nn
            i++
        }
    }

    private fun findFile(dir: DocumentFile, name: String): DocumentFile? {
        val target = name.lowercase(Locale.US)
        return dir.listFiles().firstOrNull { it.isFile && (it.name?.lowercase(Locale.US) == target) }
    }

    private fun stripExtForCreate(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (c in s) when (c) {
            '"'  -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) {
                val hex = c.code.toString(16).padStart(4, '0')
                sb.append("\\u").append(hex)
            } else sb.append(c)
        }
        sb.append('"'); return sb.toString()
    }

    private fun urlEncode(s: String): String =
        try { URLEncoder.encode(s, "UTF-8") } catch (_: Throwable) { s }

    private fun urlDecode(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (_: Throwable) { s }

    private fun copyExactly(ins: BufferedInputStream, out: java.io.OutputStream, total: Long) {
        var remain = total
        val buf = ByteArray(64 * 1024)
        while (remain > 0) {
            val n = ins.read(buf, 0, min(buf.size.toLong(), remain).toInt()); if (n <= 0) break
            out.write(buf, 0, n); remain -= n
        }
    }
}
