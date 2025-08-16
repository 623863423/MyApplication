package com.hello.http

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity() {

    // 选择保存目录
    private val pickDir = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Cfg.prefs(this).edit().putString(Cfg.KEY_ROOT_URI, uri.toString()).apply()
            Toast.makeText(this, "已选择目录：${treeName(uri)}", Toast.LENGTH_SHORT).show()
            updateInfo()
        }
    }

    // 通知权限（Android 13+）
    private val reqNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> startHttpServiceAndExit() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 顶部两行点击：复制 URL、选择目录
        findViewById<TextView>(R.id.tvPick).setOnClickListener { pickDir.launch(null) }
        findViewById<TextView>(R.id.tvUrl).setOnClickListener { copyUrlToClipboard() }
        findViewById<TextView>(R.id.tvUrlTitle).setOnClickListener { copyUrlToClipboard() }

        // 中间“启动服务”
        findViewById<TextView>(R.id.tvStart).setOnClickListener {
            val root = Cfg.prefs(this).getString(Cfg.KEY_ROOT_URI, null)
            if (root.isNullOrEmpty()) {
                Toast.makeText(this, "请先选择保存目录", Toast.LENGTH_SHORT).show()
                pickDir.launch(null)
                return@setOnClickListener
            }
            if (needNotifPermission()) {
                reqNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startHttpServiceAndExit()
            }
        }

        updateInfo()
    }

    private fun startHttpServiceAndExit() {
        ContextCompat.startForegroundService(this, Intent(this, HttpService::class.java))
        // 回到后台并收尾，保持轻量
        runCatching { moveTaskToBack(true) }
        runCatching { finishAndRemoveTask() }.onFailure { finish() }
    }

    private fun needNotifPermission(): Boolean {
        return Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }

    private fun updateInfo() {
        val tvUrl = findViewById<TextView>(R.id.tvUrl)
        val tvDir = findViewById<TextView>(R.id.tvDir)

        val port = Cfg.appPort(this)
        val host = U.firstIPv4() ?: "127.0.0.1"
        val url = "http://$host:$port"
        tvUrl.text = url

        val root = Cfg.prefs(this).getString(Cfg.KEY_ROOT_URI, null)
        tvDir.text = root?.let { treeName(Uri.parse(it)) } ?: "未选择"
    }

    private fun copyUrlToClipboard() {
        val port = Cfg.appPort(this)
        val host = U.firstIPv4() ?: "127.0.0.1"
        val url = "http://$host:$port"
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("QuickDrop URL", url))
        Toast.makeText(this, "已复制：$url", Toast.LENGTH_SHORT).show()
    }

    private fun treeName(uri: Uri): String = try {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val p = id.split(':', limit = 2)
        val vol = if (p[0] == "primary") "内部存储" else p[0]
        val path = if (p.size > 1) p[1] else ""
        if (path.isEmpty()) vol else "$vol/$path"
    } catch (_: Exception) {
        DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: uri.toString()
    }
}
