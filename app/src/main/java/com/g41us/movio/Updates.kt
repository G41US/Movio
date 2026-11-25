package com.g41us.movio

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionName: String, val apkUrl: String)

/**
 * Updates come from the latest GitHub release of G41US/Movio, published by the Release workflow
 * when version.properties changes. Android only installs them over this app when they're signed
 * with the same key.
 */
object Updates {
    private const val API = "https://api.github.com/repos/G41US/Movio/releases/latest"
    private const val DOWNLOADS = "https://github.com/G41US/Movio/releases/download/"

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "Movio-Android/${BuildConfig.VERSION_NAME}")
    }

    /** Blocking. Returns the newest release, or null when this build is already the latest. */
    fun latest(): UpdateInfo? {
        val conn = open(API)
        try {
            val code = conn.responseCode
            if (code == 404) throw IOException("No release is published yet.")
            if (code !in 200..299) throw IOException("Couldn't reach the update server. Try again later.")
            val release = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = release.optString("tag_name").removePrefix("v")
            val assets = release.optJSONArray("assets")
            val apkUrl = (0 until (assets?.length() ?: 0))
                .map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", true) }
                ?.optString("browser_download_url")
                ?.takeIf { it.startsWith(DOWNLOADS) }
                ?: throw IOException("The latest release has no app file yet. Try again in a few minutes.")
            return if (isNewer(tag, BuildConfig.VERSION_NAME)) UpdateInfo(tag, apkUrl) else null
        } finally { conn.disconnect() }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun installedName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    private fun installedCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    /** Blocking: downloads the APK and checks it's a newer Movio. [onProgress] gets 0..100, or -1. */
    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "Movio.apk.part")
        val apk = File(dir, "Movio.apk")
        val conn = open(info.apkUrl)
        try {
            if (conn.responseCode !in 200..299) throw IOException("Download failed. Try again.")
            val total = conn.contentLengthLong
            var done = 0L
            var shown = -2
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != shown) { shown = pct; onProgress(pct) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("The download was cut off. Try again.")
            if (!part.renameTo(apk)) throw IOException("Couldn't save the update.")
        } finally {
            conn.disconnect()
            part.delete()
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.path, 0)
        if (archive == null || archive.packageName != context.packageName) {
            apk.delete(); throw IOException("The downloaded file wasn't a valid update.")
        }
        if (PackageInfoCompat.getLongVersionCode(archive) <= installedCode(context)) {
            apk.delete(); throw IOException("You're on the latest version.")
        }
        return apk
    }

    /** Returns false when the user first has to allow Movio to install apps. */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return false
            } catch (_: ActivityNotFoundException) { }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
