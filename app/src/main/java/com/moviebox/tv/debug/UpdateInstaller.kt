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
import kotlinx.coroutines.flow.MutableStateFlow
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
    private const val MAX_ATTEMPTS = 3

    /** Live download state for the UI. null = idle / not downloading. While a
     *  download is in flight the banner shows [pct] + MB so the user sees it's
     *  working instead of staring at a frozen "Download" button. pct is -1 when
     *  the server didn't send a Content-Length (indeterminate). */
    data class DownloadStatus(val pct: Int, val mb: Float, val totalMb: Float)
    val downloadProgress = MutableStateFlow<DownloadStatus?>(null)

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
            downloadProgress.value = DownloadStatus(0, 0f, 0f)
            var lastError: String? = null
            // Retry a few times: GitHub asset downloads occasionally truncate
            // mid-stream, which used to leave a partial file that the system
            // installer rejected with the cryptic "problem parsing the
            // package". We now verify each attempt and only install a complete,
            // parseable APK.
            repeat(MAX_ATTEMPTS) { attempt ->
                runCatching { file.delete() }
                val total = try {
                    downloadOnce(apkUrl, version, file)
                } catch (e: Exception) {
                    Log.e(TAG, "download attempt ${attempt + 1} failed: ${e.message}", e)
                    lastError = e.message?.take(80) ?: e.javaClass.simpleName
                    return@repeat
                }
                if (isCompleteApk(file, total)) {
                    Log.i(TAG, "download verified OK: ${file.length()} bytes")
                    downloadProgress.value = DownloadStatus(
                        100, file.length() / 1_000_000f, file.length() / 1_000_000f,
                    )
                    withContext(Dispatchers.Main) { install(app, file) }
                    downloadProgress.value = null
                    return@launch
                }
                Log.e(TAG, "APK failed verification (got ${file.length()} / expected $total) " +
                    "attempt ${attempt + 1}")
                lastError = "incomplete download"
            }
            runCatching { file.delete() }
            toast(app, "Update failed (${lastError ?: "download error"}) — please try again")
            downloadProgress.value = null
        }
    }

    /** One download attempt: stream [apkUrl] into [file], pushing progress.
     *  Returns the server's Content-Length (-1 if unknown). Throws on HTTP /
     *  network error. */
    private suspend fun downloadOnce(apkUrl: String, version: String, file: File): Long {
        val req = Request.Builder()
            .url(apkUrl)
            // GitHub asks public clients to identify themselves. No
            // Authorization header — we are anonymous.
            .header("User-Agent", "vijana-barubaru-updater/$version")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            val totalMb = if (total > 0) total / 1_000_000f else 0f
            var lastReport = -1
            body.byteStream().use { input ->
                FileOutputStream(file).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var soFar = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        soFar += n
                        val pct = if (total > 0) (soFar * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            downloadProgress.value =
                                DownloadStatus(pct, soFar / 1_000_000f, totalMb)
                        }
                        if (total > 0 && pct / 10 != lastReport) {
                            lastReport = pct / 10
                            Log.i(TAG, "download $version: ${pct}%")
                        }
                    }
                }
            }
            return total
        }
    }

    /** True only if [file] is a complete, parseable APK: the byte count matches
     *  the server's Content-Length (when known) AND the zip central directory
     *  reads back with the APK's required entries. A truncated download fails
     *  the zip open, so this catches it here instead of at the installer. */
    private fun isCompleteApk(file: File, expectedSize: Long): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        if (expectedSize > 0 && file.length() != expectedSize) return false
        return runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                zip.getEntry("AndroidManifest.xml") != null &&
                    zip.getEntry("classes.dex") != null
            }
        }.getOrDefault(false)
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
