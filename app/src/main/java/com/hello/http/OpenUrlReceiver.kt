package com.hello.http

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 通知点击 → 极轻量广播中转到浏览器（更省电、更兼容）
 */
class OpenUrlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: return
        try {
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(view)
        } catch (_: Throwable) {
            // 设备无浏览器时静默；为极简省电不做额外提示
        }
    }
    companion object { const val EXTRA_URL = "extra_url" }
}
