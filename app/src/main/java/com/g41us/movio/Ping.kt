package com.g41us.movio

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Anonymous check-ins for the admin page: a random id for this device, phone or TV, and the app version,
 * sent when Movio opens and every few minutes while something plays. No names, emails or accounts.
 */
object Ping {
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun id(c: Context): String {
        val p = c.getSharedPreferences("movio_misc", Context.MODE_PRIVATE)
        return p.getString("device_id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("device_id", it).apply() }
    }

    private fun platform(c: Context) =
        if (c.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            c.getSystemService(android.app.UiModeManager::class.java)?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) "tv" else "phone"

    private fun send(c: Context, extra: JSONObject.() -> Unit = {}) {
        val app = c.applicationContext
        bg.launch {
            runCatching {
                val body = JSONObject().put("d", id(app)).put("p", platform(app)).put("v", BuildConfig.VERSION_NAME).apply(extra).toString()
                val h = URL("$HELPER/ping").openConnection() as HttpURLConnection
                h.requestMethod = "POST"; h.doOutput = true; h.connectTimeout = 8000; h.readTimeout = 8000
                h.setRequestProperty("Content-Type", "application/json")
                h.setRequestProperty("User-Agent", "Movio-Android/${BuildConfig.VERSION_NAME}")
                h.outputStream.use { it.write(body.toByteArray()) }
                h.responseCode; h.disconnect()
            }
        }
    }

    fun open(c: Context) = send(c)
    /** [kind]: "movie", "tv" or "live". */
    fun watching(c: Context, title: String, kind: String) = send(c) { put("w", title); put("k", kind) }
    fun stopped(c: Context) = send(c) { put("stop", true) }
}
