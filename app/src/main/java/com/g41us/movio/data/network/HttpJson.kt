package com.g41us.movio.data.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object HttpJson {
    fun getObject(url: String): JSONObject = open(url).use { connection ->
        connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
    }

    fun getArray(url: String): JSONArray = open(url).use { connection ->
        connection.inputStream.bufferedReader().use { JSONArray(it.readText()) }
    }

    private fun open(address: String): HttpURLConnection {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Movio-Android/0.1.0 (catalogue client)")
        connection.connect()
        val status = connection.responseCode
        if (status !in 200..299) {
            val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
            connection.disconnect()
            throw IllegalStateException("Catalogue request failed ($status): ${message.orEmpty().take(160)}")
        }
        return connection
    }
}
