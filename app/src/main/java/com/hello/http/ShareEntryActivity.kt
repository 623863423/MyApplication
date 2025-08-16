package com.hello.http

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * 系统分享 / “用本应用打开”的统一入口：不进最近任务、不留空白页。
 * 仅做参数收集与 Work 派发，快速 finish，避免占资源。
 */
class ShareEntryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = collectUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "没有可接收的内容", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 临时授权给本包，Worker 完成后会统一撤销
        val myPkg = packageName
        uris.forEach { u ->
            runCatching { grantUriPermission(myPkg, u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        // 派发给 Worker：串行队列，尽快执行（配额不足则降级）
        val data = workDataOf(ReceiveWorker.KEY_URIS to uris.map { it.toString() }.toTypedArray())
        val req = OneTimeWorkRequestBuilder<ReceiveWorker>()
            .setInputData(data)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(this)
            .enqueueUniqueWork("receive_queue", ExistingWorkPolicy.APPEND_OR_REPLACE, req)

        Toast.makeText(this, "已开始接收 ${uris.size} 个文件…", Toast.LENGTH_SHORT).show()
        finish() // 来即走，不挂最近任务
    }

    private fun collectUris(intent: Intent): List<Uri> {
        val list = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { list += it }
            Intent.ACTION_SEND_MULTIPLE -> {
                val clip: ClipData? = intent.clipData
                if (clip != null) {
                    for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { list += it }
                } else {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.addAll(it) }
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let { list += it }
        }
        return list
    }
}
