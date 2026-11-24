package com.g41us.movio.data.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object HttpJson {
    fun getObject(url: String): JSONObject = request(url) { body -> JSONObject(body) }

    fun getArray(url: String): JSONArray = request(url) { body -> JSONArray(body) }

    private fun <T> request(address: String, parse: (String) -> T): T {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Movio-Android/${com.g41us.movio.BuildConfig.VERSION_NAME} (catalogue client)")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("Catalogue request failed ($status): ${body.take(160)}")
            }
            return parse(body)
        } finally {
            connection.disconnect()
        }
    }
}
