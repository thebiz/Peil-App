package com.john.peil

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Http {
    const val USER_AGENT = "Peil (Android; github.com/thebiz/Peil-App)"

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun get(url: String, readTimeout: Int = 10_000): String = request(url, null, readTimeout)

    fun post(url: String, body: String, readTimeout: Int = 15_000): String = request(url, body, readTimeout)

    private fun request(url: String, body: String?, readTimeout: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6_000
            conn.readTimeout = readTimeout
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Language", "de")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode != 200) throw IOException("Server antwortet mit ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
