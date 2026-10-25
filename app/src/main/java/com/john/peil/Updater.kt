package com.john.peil

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Schaut beim Start bei GitHub nach, ob es eine neuere Version gibt,
 * lädt sie auf Wunsch herunter und startet die Installation.
 * Jeder Push auf GitHub erzeugt automatisch ein Release „vN“ mit der APK.
 */
class Updater(
    private val activity: Activity,
    private val onStatus: (String) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var pendingApk: File? = null

    private class Release(val version: Int, val name: String, val apkUrl: String, val notes: String)

    private fun installedVersion(): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }

    private fun installedName(): String =
        runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "?"

    fun check(manual: Boolean = false) {
        if (manual) onStatus("Suche nach Updates …")
        executor.execute {
            val release = runCatching { fetchLatest() }.getOrNull()
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                when {
                    release == null -> if (manual) onStatus("Update-Prüfung hat nicht geklappt.")
                    release.version > installedVersion() -> askToUpdate(release)
                    manual -> onStatus("Du hast schon die neueste Version (${installedName()}).")
                }
            }
        }
    }

    private fun fetchLatest(): Release {
        val json = JSONObject(get("https://api.github.com/repos/thebiz/Peil-App/releases/latest"))
        val version = json.getString("tag_name").trimStart('v', 'V').toInt()
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                return Release(version, json.optString("name").removePrefix("Peil ").ifBlank { "Build $version" }, a.getString("browser_download_url"), json.optString("body"))
            }
        }
        throw IOException("Keine APK im Release")
    }

    private fun askToUpdate(r: Release) {
        val msg = buildString {
            append("Version ${r.name} ist da. Du hast ${installedName()}.")
            if (r.notes.isNotBlank()) append("\n\n").append(r.notes.take(400))
        }
        AlertDialog.Builder(activity)
            .setTitle("Update verfügbar")
            .setMessage(msg)
            .setPositiveButton("Jetzt installieren") { _, _ -> download(r) }
            .setNegativeButton("Später", null)
            .show()
    }

    private fun download(r: Release) {
        onStatus("Lade Update ${r.name} …")
        executor.execute {
            val result = runCatching {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "Peil-v${r.version}.apk")
                val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 30_000
                    conn.instanceFollowRedirects = true
                    if (conn.responseCode != 200) throw IOException("Download: ${conn.responseCode}")
                    conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                } finally {
                    conn.disconnect()
                }
                file
            }
            activity.runOnUiThread {
                result.onSuccess { install(it) }
                    .onFailure { onStatus("Download hat nicht geklappt: ${it.message}") }
            }
        }
    }

    private fun install(apk: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            // Einmalig erlauben, dass Peil Updates installieren darf
            pendingApk = apk
            onStatus("Bitte „Aus dieser Quelle zulassen“ einschalten und zurückkommen.")
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        pendingApk = null
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        onStatus("Update wird installiert …")
        activity.startActivity(intent)
    }

    /** Aus onResume aufrufen: Falls gerade die Erlaubnis erteilt wurde, Installation fortsetzen. */
    fun resumePendingInstall() {
        val apk = pendingApk ?: return
        if (activity.packageManager.canRequestPackageInstalls() && apk.exists()) install(apk)
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Peil-Updater")
            if (conn.responseCode != 200) throw IOException("GitHub: ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
