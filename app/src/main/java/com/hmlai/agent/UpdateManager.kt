package com.hmlai.agent

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * In-app updates.
 *
 * GitHub Actions publishes every successful build as a Release tagged "build-<n>" with the
 * APK attached (see .github/workflows/build-debug.yml). This class asks GitHub for the newest
 * release, compares <n> with BuildConfig.BUILD_NUMBER, downloads the APK and hands it to
 * Android's PackageInstaller. The APK is signed with the same keystore every time, so Android
 * treats it as an update of the installed app and keeps all data.
 *
 * Requires the release to be publicly downloadable (public repository).
 */
object UpdateManager {

    data class UpdateInfo(
        val buildNumber: Int,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long
    )

    private const val PREFS = "hml_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_DISMISSED_BUILD = "dismissed_build"
    private const val KEY_DISMISSED_AT = "dismissed_at"
    const val KEY_INSTALLING_SINCE = "installing_since"

    private const val AUTO_CHECK_INTERVAL_MS = 15 * 60 * 1000L
    private const val DISMISS_QUIET_MS = 3 * 60 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Set while we wait for the user to grant "install unknown apps"; resumed from onResume. */
    @Volatile
    var pendingInfo: UpdateInfo? = null

    fun isConfigured(): Boolean = BuildConfig.UPDATE_REPO.contains("/")

    fun currentBuild(): Int = BuildConfig.BUILD_NUMBER

    // ------------------------------------------------------------------ checking

    /** Calls back on the main thread with (update or null, error text or null). */
    fun check(onResult: (UpdateInfo?, String?) -> Unit) {
        if (!isConfigured()) {
            onResult(null, "Updates aren't set up in this build.")
            return
        }
        Thread {
            var info: UpdateInfo? = null
            var error: String? = null
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body == null) {
                        error = if (response.code == 404) {
                            "No published release found (is the repository public?)."
                        } else {
                            "GitHub answered ${response.code}."
                        }
                    } else {
                        info = parseRelease(JSONObject(body))
                    }
                }
            } catch (e: Exception) {
                error = "Couldn't reach GitHub: ${e.message ?: "network error"}"
            }
            main.post { onResult(info, error) }
        }.start()
    }

    /** Returns an UpdateInfo only if the release is NEWER than the running build. */
    private fun parseRelease(json: JSONObject): UpdateInfo? {
        val tag = json.optString("tag_name", "")
        val number = Regex("(\\d+)").find(tag)?.value?.toIntOrNull() ?: return null
        if (number <= BuildConfig.BUILD_NUMBER) return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                return UpdateInfo(
                    buildNumber = number,
                    title = json.optString("name", "HML Agent build $number"),
                    notes = json.optString("body", "").trim(),
                    apkUrl = asset.optString("browser_download_url"),
                    sizeBytes = asset.optLong("size", 0L)
                )
            }
        }
        return null
    }

    /** Throttled check used on app start / resume. Calls back only when an update should be offered. */
    fun autoCheck(context: Context, onAvailable: (UpdateInfo) -> Unit) {
        if (!isConfigured()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < AUTO_CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        check { info, _ ->
            if (info == null) return@check
            val dismissedBuild = prefs.getInt(KEY_DISMISSED_BUILD, -1)
            val dismissedAt = prefs.getLong(KEY_DISMISSED_AT, 0L)
            val recentlyDismissed = dismissedBuild == info.buildNumber &&
                System.currentTimeMillis() - dismissedAt < DISMISS_QUIET_MS
            if (!recentlyDismissed) onAvailable(info)
        }
    }

    fun markDismissed(context: Context, info: UpdateInfo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DISMISSED_BUILD, info.buildNumber)
            .putLong(KEY_DISMISSED_AT, System.currentTimeMillis())
            .apply()
    }

    // ------------------------------------------------------------------ installing

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" page for HML Agent. */
    fun openInstallPermissionSettings(context: Context) {
        UpdateAutoApprover.arm()
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Downloads the APK and starts the install. All callbacks arrive on the main thread.
     * onProgress: 0..100 (or -1 when the size is unknown). onFinished: null on success, else an error.
     */
    fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onFinished: (String?) -> Unit
    ) {
        val app = context.applicationContext
        Thread {
            var error: String? = null
            try {
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "hml-agent-${info.buildNumber}.apk")

                val request = Request.Builder().url(info.apkUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Download failed (${response.code}).")
                    val body = response.body ?: throw IllegalStateException("Empty download.")
                    val total = if (body.contentLength() > 0) body.contentLength() else info.sizeBytes
                    var done = 0L
                    var lastPercent = -2
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    main.post { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
                if (file.length() < 100_000L) throw IllegalStateException("The downloaded file looks incomplete.")
                installApk(app, file)
            } catch (e: Exception) {
                error = e.message ?: "Update failed."
            }
            main.post { onFinished(error) }
        }.start()
    }

    private fun installApk(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: after the first approved update, later updates from the same app
            // can install without another confirmation screen.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            file.inputStream().use { input ->
                session.openWrite("hml-agent", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_INSTALLING_SINCE, System.currentTimeMillis()).apply()

            val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(UpdateInstallReceiver.ACTION_STATUS)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            UpdateAutoApprover.arm()
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    fun wasInstallingRecently(context: Context): Boolean {
        val since = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_INSTALLING_SINCE, 0L)
        return since > 0 && System.currentTimeMillis() - since < 10 * 60 * 1000L
    }

    fun clearInstalling(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_INSTALLING_SINCE).apply()
    }
}

/**
 * Lets HML's Accessibility Service tap "Update"/"Install" on the system installer screen (and
 * flip "Allow from this source") — but ONLY for a short window after the user themselves
 * pressed Update inside HML Agent. If the system blocks this, the user just taps the button.
 */
object UpdateAutoApprover {
    @Volatile
    private var armedUntil = 0L

    fun arm(durationMs: Long = 150_000L) {
        armedUntil = System.currentTimeMillis() + durationMs
    }

    fun disarm() {
        armedUntil = 0L
    }

    fun isArmed(): Boolean = System.currentTimeMillis() < armedUntil

    fun isInstallerPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("packageinstaller") || p.contains("appdetail") ||
            p == "com.android.settings" || p.contains("securitypermission") ||
            p.contains("installer")
    }
}
