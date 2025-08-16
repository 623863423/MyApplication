package com.hello.http

import java.net.NetworkInterface
import java.net.Inet4Address
import android.webkit.MimeTypeMap
import java.util.Locale

/**
 * 轻量工具集合：统一网络、格式化、MIME、文件名清洗等。
 * 仅包含纯函数/静态能力，避免持有 Context 以降低耦合与风险。
 */
object U {

    /** 第一个非回环/非虚拟 IPv4；用于通知与 UI 显示。 */
    fun firstIPv4(): String? {
        return try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return null
            while (en.hasMoreElements()) {
                val nif = en.nextElement()
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    /** 人类可读文件大小。 */
    fun human(n: Long): String = when {
        n >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", n / (1L shl 30).toDouble())
        n >= 1L shl 20 -> String.format(Locale.US, "%.2f MB", n / (1L shl 20).toDouble())
        n >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", n / (1L shl 10).toDouble())
        else -> "$n B"
    }

    /** 依据扩展名推断 MIME；兜底 application/octet-stream。 */
    fun guessMime(name: String): String {
        val dot = name.lastIndexOf('.')
        val ext = if (dot >= 0 && dot + 1 < name.length) name.substring(dot + 1).lowercase(Locale.US) else ""
        if (ext.isEmpty()) return "application/octet-stream"
        val mtm = MimeTypeMap.getSingleton()
        val m = runCatching { mtm.getMimeTypeFromExtension(ext) }.getOrNull()
        return m ?: "application/octet-stream"
    }

    /** 清洗文件名，去除非法字符并兜底。 */
    fun safeName(name: String): String {
        val base = name.trim().replace("\u0000", "")
        val bad = charArrayOf('\\','/',';',':','*','?','"','<','>','|')
        val s = base.filter { it !in bad }
        return if (s.isEmpty()) "file.bin" else s
    }
}
