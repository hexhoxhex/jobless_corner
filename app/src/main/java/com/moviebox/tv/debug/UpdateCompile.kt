package com.moviebox.tv.debug

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.profileinstaller.DeviceProfileWriter
import androidx.profileinstaller.ProfileInstaller
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Install an update already compiled, instead of leaving it to run
 * interpreted until the TV compiles it on its own overnight.
 *
 * Measured on the TV (TV performance notes, v0.1.278): a sideloaded app runs
 * uncompiled (dexopt "verify") after every install: ~50% janky frames while
 * browsing against ~10% compiled, until idle maintenance on standby. The
 * baseline profile in the APK only helps once Android compiles with it.
 *
 * Android compiles at install time with a profile when one arrives WITH the
 * APK, as a dex-metadata file `base.dm` in the same install session (the TV
 * and the emulator both run `pm.dexopt.install=speed-profile`). The system
 * installer screen we used to hand the APK to (ACTION_VIEW) cannot carry
 * one, so updates now go through a PackageInstaller session of our own:
 * base.apk plus base.dm, the system still asking the viewer to confirm.
 *
 * The profile inside the .dm must be in this device's ART format; the
 * profileinstaller library already knows how to transcode the APK's
 * baseline profile for it (it does exactly that for the running app on
 * first launch), so it is pointed at the NEW APK's assets instead.
 *
 * Every step falls back: no .dm -> APK alone in the session; no session ->
 * the old installer screen. An update must never fail because of this.
 */
internal object UpdateCompile {

    private const val TAG = "UpdateInstaller"
    const val EXTRA_APK = "com.moviebox.tv.update.APK"

    /** A `.dm` next to [apk] holding its baseline profile transcoded for this
     *  device, or null when the device or the APK can't provide one. */
    @SuppressLint("RestrictedApi")
    fun buildDexMetadata(context: Context, apk: File): File? = runCatching {
        val pm = context.packageManager
        val ai = pm.getPackageArchiveInfo(apk.path, 0)?.applicationInfo ?: return null
        ai.sourceDir = apk.path
        ai.publicSourceDir = apk.path
        val assets = pm.getResourcesForApplication(ai).assets
        val prof = File(apk.parentFile, apk.nameWithoutExtension + ".prof")
        prof.delete()
        val noop = object : ProfileInstaller.DiagnosticsCallback {
            override fun onDiagnosticReceived(code: Int, data: Any?) = Unit
            override fun onResultReceived(code: Int, data: Any?) {
                Log.i(TAG, "profile transcode result=$code")
            }
        }
        val writer = DeviceProfileWriter(
            assets, { it.run() }, noop, "base.apk",
            "dexopt/baseline.prof", "dexopt/baseline.profm", prof,
        )
        if (!writer.deviceAllowsProfileInstallerAotWrites()) return null
        if (!writer.read().transcodeIfNeeded().write() || prof.length() == 0L) {
            prof.delete()
            return null
        }
        val bytes = prof.readBytes()
        prof.delete()
        val dm = File(apk.parentFile, apk.nameWithoutExtension + ".dm")
        ZipOutputStream(FileOutputStream(dm)).use { z ->
            // Stored, not deflated, as the platform's own .dm files are.
            val entry = ZipEntry("primary.prof").apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().apply { update(bytes) }.value
            }
            z.putNextEntry(entry)
            z.write(bytes)
            z.closeEntry()
        }
        Log.i(TAG, "dex metadata built: ${dm.length()} bytes (profile ${bytes.size})")
        dm
    }.onFailure { Log.w(TAG, "dex metadata failed: ${it.message}") }.getOrNull()

    /** Commit [apk] (and [dm] if any) in an install session. The system asks
     *  the viewer to confirm via [UpdateResultReceiver]. False when the
     *  session could not even be committed — use the installer screen. */
    fun installWithSession(context: Context, apk: File, dm: File?): Boolean {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length() + (dm?.length() ?: 0L))
        }
        val id = runCatching { installer.createSession(params) }.getOrElse {
            Log.w(TAG, "createSession failed: ${it.message}")
            return false
        }
        return try {
            installer.openSession(id).use { s ->
                write(s, "base.apk", apk)
                if (dm != null) write(s, "base.dm", dm)
                val intent = Intent(context, UpdateResultReceiver::class.java)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_APK, apk.path)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, id, intent, flags)
                s.commit(pending.intentSender)
            }
            Log.i(TAG, "install session $id committed (dm=${dm != null})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "install session failed: ${e.message}")
            runCatching { installer.abandonSession(id) }
            false
        }
    }

    private fun write(s: PackageInstaller.Session, name: String, f: File) {
        s.openWrite(name, 0, f.length()).use { out ->
            f.inputStream().use { it.copyTo(out, 256 * 1024) }
            s.fsync(out)
        }
    }
}

/** Result of an update install session: shows the system's confirmation,
 *  and if the session fails for any reason other than the viewer saying no,
 *  hands the APK alone to the installer screen as updates always went. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("UpdateInstaller", "install session status=$status $message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { Log.w("UpdateInstaller", "confirm failed: ${it.message}") }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            // The viewer declined. Asking again at once would be nagging.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val apk = intent.getStringExtra(UpdateCompile.EXTRA_APK)?.let(::File) ?: return
                UpdateInstaller.installViaInstallerScreen(context, apk)
            }
        }
    }
}
