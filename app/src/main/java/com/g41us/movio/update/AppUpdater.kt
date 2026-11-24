package com.g41us.movio.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.g41us.movio.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Downloads the release APK inside the app and hands it to Android's installer. */
object AppUpdater {

    /** Android 8+ asks the user once to allow Movio to install updates. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Returns the downloaded, verified APK. [onProgress] gets 0f..1f, or -1f if size is unknown. */
    suspend fun download(context: Context, apkUrl: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(apkUrl.startsWith("https://github.com/G41US/Movio/releases/download/")) {
                "Update URL is not from the Movio releases page"
            }
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "movio-update.apk")

            val connection = URL(apkUrl).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "Movio-Android/${BuildConfig.VERSION_NAME}")
                val status = connection.responseCode
                if (status !in 200..299) throw IllegalStateException("Download failed (HTTP $status)")
                val total = connection.contentLengthLong
                var read = 0L
                var lastReported = -1
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != lastReported) {
                                    lastReported = pct
                                    withContext(Dispatchers.Main) { onProgress(pct / 100f) }
                                }
                            }
                        }
                    }
                }
                if (total <= 0) withContext(Dispatchers.Main) { onProgress(-1f) }
            } finally {
                connection.disconnect()
            }

            // Refuse anything that isn't a newer Movio build.
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(target.absolutePath, 0)
                ?: throw IllegalStateException("Downloaded file is not a valid APK")
            if (info.packageName != context.packageName) throw IllegalStateException("Downloaded APK is not Movio")
            @Suppress("DEPRECATION")
            val newCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            if (newCode <= BuildConfig.VERSION_CODE) throw IllegalStateException("Downloaded APK is not newer than this version")
            target
        }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
