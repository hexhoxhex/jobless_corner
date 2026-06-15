package com.moviebox.tv.debug

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Downloads a release APK via the system DownloadManager and pops the
 * install confirmation so the upgrade lands in one tap. The whole thing
 * runs from the device — no GitHub visit needed.
 *
 * Why DownloadManager: it survives recomposition, handles redirects,
 * shows a system notification with progress, and resumes cleanly across
 * Wi-Fi flaps. Far less code than rolling OkHttp + a foreground service.
 *
 * Why FileProvider: from Nougat onwards `Intent.ACTION_VIEW` on a `file://`
 * URI throws `FileUriExposedException`. The provider hands the installer
 * a `content://` URI that's grant-readable across processes.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"

    /**
     * Kick off the download. The receiver registered below fires the install
     * intent once the file is on disk. Returns the download ID, or -1 if
     * we couldn't enqueue (no DownloadManager, malformed URL, etc.).
     */
    fun download(context: Context, apkUrl: String, version: String): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return -1L
        registerReceiverOnce(context)
        val filename = "vijanabarubaru-$version.apk"
        // Clean any stale copy with the same name — DownloadManager won't
        // overwrite by default and would silently append " (1)" etc.
        runCatching {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), filename).delete()
        }
        val req = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Vijana BaruBaru update")
            .setDescription("Downloading $version…")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                filename,
            )
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setAllowedOverMetered(true)
        val id = dm.enqueue(req)
        Log.i(TAG, "enqueued $apkUrl as id=$id")
        return id
    }

    /** Tracks in-flight downloads so the broadcast receiver knows which file
     *  to install when DownloadManager fires its completion broadcast. */
    private val pending = mutableMapOf<Long, File>()

    @Volatile private var receiverRegistered = false

    /** Register a process-wide receiver exactly once. */
    @Synchronized
    private fun registerReceiverOnce(context: Context) {
        if (receiverRegistered) return
        val app = context.applicationContext
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    ?: return
                onDownloadComplete(app, id)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                app, receiver, filter, ContextCompat.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun onDownloadComplete(app: Context, id: Long) {
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val q = DownloadManager.Query().setFilterById(id)
        dm.query(q)?.use { c ->
            if (!c.moveToFirst()) return
            val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val uriCol = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            val status = if (statusCol >= 0) c.getInt(statusCol) else -1
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Log.w(TAG, "download $id ended with status=$status")
                return
            }
            val localUri = if (uriCol >= 0) c.getString(uriCol) else null
            val path = localUri?.let { Uri.parse(it).path } ?: return
            install(app, File(path))
        }
    }

    /**
     * Open the system install confirmation for [apk]. The user taps once
     * and the upgrade replaces this very process. Because the new APK is
     * signed with the same key (assuming the CI release pipeline) Android
     * preserves the app data — watch history, favorites, pair tokens.
     *
     * Returns true if the intent was dispatched; false if we couldn't
     * resolve a URI for the file (sanity-check during dev).
     */
    fun install(context: Context, apk: File): Boolean {
        if (!apk.exists() || apk.length() == 0L) {
            Log.w(TAG, "install skipped — file missing or empty: ${apk.path}")
            return false
        }
        val authority = "${context.packageName}.updates"
        val uri = runCatching {
            FileProvider.getUriForFile(context, authority, apk)
        }.getOrElse {
            Log.e(TAG, "FileProvider failed: ${it.message}", it)
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Log.e(TAG, "install intent failed: ${it.message}", it)
            return false
        }
        return true
    }
}
