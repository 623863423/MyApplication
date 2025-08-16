package com.hello.http

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters

/**
 * 后台接收：仅在需要时以前台方式运行；完成后撤销 URI 授权 + 清理历史，不残留。
 */
class ReceiveWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo("正在接收…"))

        val uriStrs = inputData.getStringArray(KEY_URIS) ?: emptyArray()
        if (uriStrs.isEmpty()) return Result.success()

        val rootStr = Cfg.prefs(applicationContext).getString(Cfg.KEY_ROOT_URI, null) ?: return Result.success()
        val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(rootStr)) ?: return Result.success()

        var ok = 0
        val toRevoke = ArrayList<Uri>()
        try {
            for (s in uriStrs) {
                val src = Uri.parse(s)
                toRevoke += src

                // 解析文件名/大小
                val (name0, _) = queryNameSize(src)
                val name = U.safeName(name0)
                val target = root.createFile(U.guessMime(name), name) ?: continue

                applicationContext.contentResolver.openInputStream(src)?.use { input ->
                    applicationContext.contentResolver.openOutputStream(target.uri)?.use { output ->
                        input.copyTo(output)
                    }
                }
                ok++
                setForeground(createForegroundInfo("接收中…($ok/${uriStrs.size})"))
            }
            return Result.success()
        } finally {
            // 统一撤销读取授权，确保“传完即净”
            toRevoke.forEach {
                runCatching {
                    applicationContext.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            // 清理 Work 历史，避免累计
            runCatching { WorkManager.getInstance(applicationContext).pruneWork() }
        }
    }

    private fun queryNameSize(u: Uri): Pair<String, Long> {
        var name = "file.bin"
        var size = -1L
        applicationContext.contentResolver.query(
            u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val iN = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val iS = c.getColumnIndex(OpenableColumns.SIZE)
                if (iN >= 0) name = c.getString(iN) ?: name
                if (iS >= 0) size = c.getLong(iS)
            }
        }
        return name to size
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "QuickDrop", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = NotificationCompat.Builder(applicationContext, CH_ID)
            // 用系统内置小图标，避免缺资源导致的编译错误
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("接收文件")
            .setContentText(text)
            .setOngoing(true)
            .build()
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIF_ID, n, type)
        else ForegroundInfo(NOTIF_ID, n)
    }

    companion object {
        const val KEY_URIS = "uris"
        private const val CH_ID = "quickdrop"
        private const val NOTIF_ID = 11
    }
}
