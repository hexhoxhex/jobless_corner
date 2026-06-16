package com.moviebox.tv.debug

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads a release APK from a public URL (the GitHub release page redirect
 * chain typically resolves to release-assets.githubusercontent.com with a
 * signed JWT) and pops the install confirmation so the upgrade lands in one
 * tap. The whole thing runs from the device — no GitHub visit needed.
 *
 * **Why not DownloadManager.** Earlier versions of this updater used the
 * system DownloadManager. Multiple users reported the install failing with
 * an HTTP 401. The root cause is a known quirk: when DownloadManager
 * follows GitHub's 302 redirect from the public download URL to the signed
 * `release-assets.githubusercontent.com` URL, some ROMs leak the original
 * request's headers (or a stale cookie from the cookie jar) onto the
 * redirect target. The signed-URL endpoint rejects unexpected auth with
 * 401 instead of ignoring it.
 *
 * OkHttp's redirect follower discards cross-host auth headers per RFC 7235
 * / RFC 9110, which keeps the signed URL request clean. Bonus: we get
 * actionable error reporting (HTTP code, exception type) into our own
 * logs instead of opaque DownloadManager.STATUS_FAILED codes.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Kick off the download. Non-blocking; returns the in-flight Job so
     *  callers can cancel if the user backs out. Errors surface as toasts
     *  + LiveDiag logs; on success the system installer pops up. */
    fun download(context: Context, apkUrl: String, version: String): Job {
        val app = context.applicationContext
        return scope.launch {
            val file = destinationFile(app, version)
            runCatching { file.delete() }
            toast(app, "Downloading update…")
            try {
                val req = Request.Builder()
                    .url(apkUrl)
                    // GitHub asks public clients to identify themselves.
                    // No Authorization header — we are anonymous.
                    .header("User-Agent", "vijana-barubaru-updater/$version")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "download failed HTTP ${resp.code} from $apkUrl")
                        toast(app, "Update failed: HTTP ${resp.code}")
                        return@launch
                    }
                    val body = resp.body ?: run {
                        toast(app, "Update failed: empty body")
                        return@launch
                    }
                    val total = body.contentLength().takeIf { it > 0 } ?: -1L
                    body.byteStream().use { input ->
                        FileOutputStream(file).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var soFar = 0L
                            var lastReport = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                soFar += n
                                if (total > 0) {
                                    val pct = (soFar * 100 / total).toInt()
                                    // Don't spam logcat — only on 10%
                                    // boundaries.
                                    if (pct / 10 != lastReport) {
                                        lastReport = pct / 10
                                        Log.i(TAG, "download $version: ${pct}%")
                                    }
                                }
                            }
                        }
                    }
                }
                Log.i(TAG, "download complete: ${file.absolutePath} (${file.length()} bytes)")
                withContext(Dispatchers.Main) { install(app, file) }
            } catch (e: Exception) {
                Log.e(TAG, "download exception: ${e.message}", e)
                toast(app, "Update failed: ${e.message?.take(80) ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun destinationFile(context: Context, version: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.cacheDir
        if (!dir.exists()) dir.mkdirs()
        // Match the FileProvider path declared in res/xml/update_provider_paths.xml.
        return File(dir, "vijanabarubaru-$version.apk")
    }

    private suspend fun toast(context: Context, message: String) {
        withContext(Dispatchers.Main) {
            runCatching { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
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
