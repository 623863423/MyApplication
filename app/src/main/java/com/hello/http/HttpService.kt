package com.hello.http

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 前台 HTTP 服务：串行启动、稳点击、统一彻底收
 */
class HttpService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var server: TinyHttpServer? = null
    private val startMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 任何启动请求都进入串行确保流程
        scope.launch { ensureStartedSerial() }
        return START_STICKY
    }

    /** 串行化启动，避免竞态；已在运行则仅刷新通知与状态 */
    private suspend fun ensureStartedSerial() = startMutex.withLock {
        server?.let {
            updateRunningNotificationAsync()
            Cfg.setState(this@HttpService, Cfg.SrvState.RUNNING)
            return
        }

        val sp = Cfg.prefs(this@HttpService)
        val rootStr = sp.getString(Cfg.KEY_ROOT_URI, null)
        val root = rootStr?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (root == null) {
            stopAndClean("no-root")
            return
        }
        val port = Cfg.appPort(this@HttpService)

        // 1) 发布“启动中…”前台通知
        withContext(Dispatchers.Main) {
            val n = buildStatusNotification(
                title = "启动中…",
                url = "http://${U.firstIPv4() ?: "127.0.0.1"}:$port"
            )
            startForeground(NOTIF_ID, n)
            Cfg.setState(this@HttpService, Cfg.SrvState.STARTING)
        }

        // 2) 尝试启动服务器（指数回退重试）
        val backoffs = longArrayOf(150, 300, 600, 1000, 1500, 2000)
        var lastErr: Throwable? = null
        for (d in backoffs) {
            try {
                val s = TinyHttpServer(
                    context = this@HttpService,
                    rootUri = root,
                    port = port,
                    pin = "5555",
                    onExit = { stopSelf() }, // /exit 后由 Service 做统一收尾
                    onReady = {
                        // 绑定成功 → “已启动”
                        updateRunningNotificationAsync()
                        Cfg.setState(this@HttpService, Cfg.SrvState.RUNNING)
                    }
                )
                server = s
                s.start() // 阻塞直到 close()
                // start() 返回表示服务器已关闭（/exit 或 onDestroy），Service 生命周期继续走
                return
            } catch (e: Throwable) {
                lastErr = e
                delay(d)
            }
        }

        // 3) 多次失败：发失败通知→统一收尾
        withContext(Dispatchers.Main) {
            val host = U.firstIPv4() ?: "127.0.0.1"
            val url = "http://$host:$port"
            val n = NotificationCompat.Builder(this@HttpService, CH_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("启动失败")
                .setContentText("$url · 端口占用/权限问题")
                .setOnlyAlertOnce(true)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
        }
        stopAndClean("start-failed:${lastErr?.javaClass?.simpleName ?: "unknown"}")
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CH_ID, "QuickDrop", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    /** “已启动”通知：点击经 BroadcastReceiver 更稳打开浏览器 */
    private fun buildRunningNotification(host: String, port: Int): Notification {
        val url = "http://$host:$port"
        val open = Intent(this, OpenUrlReceiver::class.java).apply {
            putExtra(OpenUrlReceiver.EXTRA_URL, url)
        }
        val reqCode = url.hashCode()
        val flags = (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0) or
                PendingIntent.FLAG_CANCEL_CURRENT
        val pi = PendingIntent.getBroadcast(this, reqCode, open, flags)

        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("服务已启动")
            .setContentText(url)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** “启动中/停止中”通用通知（无点击） */
    private fun buildStatusNotification(title: String, url: String): Notification {
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(url)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** 服务器就绪→刷新为真实 IPv4 + “服务已启动”文案 */
    private fun updateRunningNotificationAsync() {
        scope.launch(Dispatchers.Main) {
            val host = U.firstIPv4() ?: "127.0.0.1"
            val port = Cfg.appPort(this@HttpService)
            val n = buildRunningNotification(host, port)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
        }
    }

    /** 统一彻底收尾（任何出口都走这里），避免残留与卡通知 */
    private fun stopAndClean(reason: String) {
        // 1) 关服务器（释放端口+线程），额外 300ms 等内核收尾在 TinyHttpServer.close() 里做
        runCatching { server?.close() }
        server = null

        // 2) 停止前台 + 取消通知
        runCatching { stopForeground(true) }
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) }

        // 3) 状态置 IDLE
        Cfg.setState(this@HttpService, Cfg.SrvState.IDLE)
    }

    override fun onDestroy() {
        // Service 即将结束：做一次强收尾，确保网页 /exit 路径外也不留残
        stopAndClean("destroy")
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CH_ID = "quickdrop"
        private const val NOTIF_ID = 1

        fun ensureRunning(ctx: Context) {
            val it = Intent(ctx, HttpService::class.java)
            ContextCompat.startForegroundService(ctx, it)
        }
    }
}
