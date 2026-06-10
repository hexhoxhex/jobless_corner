package com.moviebox.tv.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.moviebox.tv.R
import com.moviebox.tv.data.local.AppDatabase
import com.moviebox.tv.data.local.DownloadDao
import com.moviebox.tv.data.local.DownloadEntity
import com.moviebox.tv.data.local.DownloadStatus
import com.moviebox.tv.net.Constants
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service that downloads queued items sequentially using OkHttp with
 * the CDN headers, writing to app-internal storage and tracking progress in Room.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dao: DownloadDao
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        dao = AppDatabase.get(this).downloads()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification("Preparing downloads…", 0, 0))
        if (!running) {
            running = true
            scope.launch { processQueue() }
        }
        return START_STICKY
    }

    private suspend fun processQueue() {
        while (true) {
            val item = dao.nextQueued() ?: break
            dao.updateStatus(item.key, DownloadStatus.DOWNLOADING.name)
            runCatching { download(item) }
                .onSuccess { dao.updateStatus(item.key, DownloadStatus.COMPLETED.name) }
                .onFailure { dao.updateStatus(item.key, DownloadStatus.FAILED.name) }
        }
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun download(item: DownloadEntity) {
        val dir = File(item.filePath).parentFile
        dir?.mkdirs()

        // Subtitles first (small); rewrite their JSON with local paths.
        val localSubs = JSONArray()
        val subs = JSONArray(item.subtitleJson)
        for (i in 0 until subs.length()) {
            val s = subs.getJSONObject(i)
            val subFile = File(dir, "${item.key.replace('|', '_')}.${s.getString("code")}.srt")
            runCatching { fetchToFile(s.getString("url"), subFile) }
            localSubs.put(
                JSONObject()
                    .put("code", s.getString("code"))
                    .put("name", s.getString("name"))
                    .put("path", subFile.absolutePath)
            )
        }

        // Media file with progress.
        val request = Request.Builder().url(item.mediaUrl).apply {
            Constants.mediaHeaders.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body ?: error("Empty body")
            val total = body.contentLength()
            val file = File(item.filePath)
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - last > 400_000_000L) { // ~0.4s
                            dao.updateProgress(item.key, done, total)
                            notifyManager().notify(
                                NOTIF_ID, notification(item.title, done, total),
                            )
                            last = now
                        }
                    }
                    dao.updateProgress(item.key, done, total)
                }
            }
        }
        // Persist local subtitle paths.
        dao.upsert(item.copy(subtitleJson = localSubs.toString()))
    }

    private fun fetchToFile(url: String, file: File) {
        val req = Request.Builder().url(url).apply {
            Constants.mediaHeaders.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(req).execute().use { r ->
            r.body?.byteStream()?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
    }

    private fun notifyManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notification(title: String, done: Long, total: Long): Notification {
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW,
            )
            notifyManager().createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
