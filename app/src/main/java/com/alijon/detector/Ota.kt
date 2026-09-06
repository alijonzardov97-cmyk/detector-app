package com.alijon.detector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A firmware build published for one model. */
data class FirmwareBuild(val model: String, val version: String, val url: String, val size: Long)

/**
 * Firmware source: GitHub Releases.
 *
 * An asset must be named  <model>-<version>.bin  e.g.  PI-1-1.4.0.bin
 * The model in the file name is checked against the model the device reported,
 * so a build for another model can never be sent to this one.
 */
class Firmware(private val repo: String) {

    private val assetRe = Regex("""^([A-Za-z0-9_\-]+)-(\d+)\.(\d+)\.(\d+)\.bin$""")

    /** Newest build for this exact model, or null if the release has none. */
    suspend fun latestFor(model: String): FirmwareBuild? = withContext(Dispatchers.IO) {
        val json = get("https://api.github.com/repos/$repo/releases/latest")
        val assets = JSONObject(json).optJSONArray("assets") ?: return@withContext null
        var best: FirmwareBuild? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val m = assetRe.find(a.getString("name")) ?: continue
            if (!m.groupValues[1].equals(model, ignoreCase = true)) continue
            val b = FirmwareBuild(
                model = m.groupValues[1],
                version = "${m.groupValues[2]}.${m.groupValues[3]}.${m.groupValues[4]}",
                url = a.getString("browser_download_url"),
                size = a.optLong("size"),
            )
            if (best == null || compare(b.version, best!!.version) > 0) best = b
        }
        best
    }

    suspend fun download(build: FirmwareBuild): ByteArray = withContext(Dispatchers.IO) {
        val conn = (URL(build.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000; readTimeout = 60000
        }
        conn.inputStream.use { it.readBytes() }
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 15000; readTimeout = 20000
        }
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    companion object {
        /** Positive when a is newer than b. */
        fun compare(a: String, b: String): Int {
            val x = a.split(".").map { it.toIntOrNull() ?: 0 }
            val y = b.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until 3) {
                val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0)
                if (d != 0) return d
            }
            return 0
        }
    }
}
