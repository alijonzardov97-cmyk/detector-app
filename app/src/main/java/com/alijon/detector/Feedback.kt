package com.alijon.detector

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * VCO audio generated on the phone.
 *
 * The detector reports 8 discrete steps, so a raw mapping would sound like a
 * staircase. Two things fix that: a threshold hum that is always present, and
 * a glide - the frequency moves toward its target instead of jumping. That is
 * what makes the classic "wooo" over a target.
 *
 * Latency is the BLE hop only (roughly 50-100 ms with wired headphones), far
 * better than streaming A2DP audio from the detector itself.
 */
class VcoAudio {

    var enabled: Boolean = false
        set(v) { field = v; if (v) start() else stop() }

    /** 0..levels, set from telemetry. */
    @Volatile var level: Float = 0f
    @Volatile var levels: Int = 8
    /** 0..1 */
    @Volatile var volume: Float = 0.7f

    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    private val rate = 22050
    private val thresholdHz = 240f      // фон, слышен всегда
    private val topHz = 1150f           // сильная цель
    private val glide = 0.12f           // 0..1, больше - резче

    private fun start() {
        if (running) return
        running = true
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }

        worker = Thread {
            val buf = ShortArray(512)
            var phase = 0.0
            var freq = thresholdHz
            var amp = 0.10f
            while (running) {
                val t = (level / levels.coerceAtLeast(1)).coerceIn(0f, 1f)
                val wantFreq = thresholdHz + (topHz - thresholdHz) * t
                val wantAmp = 0.10f + 0.90f * t          // фон тихий, цель громкая
                for (i in buf.indices) {
                    freq += (wantFreq - freq) * glide / buf.size
                    amp += (wantAmp - amp) * glide / buf.size
                    phase += 2.0 * PI * freq / rate
                    if (phase > 2 * PI) phase -= 2 * PI
                    buf[i] = (sin(phase) * amp * volume * 26000).toInt().toShort()
                }
                // Поток живёт своей жизнью: если дорожку освободили между
                // проверкой и записью, исключение здесь уронило бы всё
                // приложение — оно ведь не на главном потоке.
                try { track?.write(buf, 0, buf.size) } catch (_: Throwable) { break }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stop() {
        running = false
        worker?.join(300); worker = null
        track?.run { pause(); flush(); release() }
        track = null
    }
}

/** Short buzz when a target crosses the threshold, stronger for stronger targets. */
class Haptics(ctx: Context) {

    @Suppress("DEPRECATION")
    private val vib: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    var enabled = false
    var triggerLevel = 5

    private var wasAbove = false

    fun onLevel(level: Int) {
        val above = level >= triggerLevel
        if (enabled && above && !wasAbove) buzz(level)
        wasAbove = above
    }

    private fun buzz(level: Int) {
        val v = vib ?: return
        // Значения зажимаем: createOneShot бросает исключение на нулевой
        // длительности и на амплитуде вне 1..255, а level приходит из эфира.
        val ms = (25L + level.coerceAtLeast(0) * 6L).coerceIn(20L, 400L)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = (80 + level * 20).coerceIn(1, 255)
                v.vibrate(VibrationEffect.createOneShot(ms, amp))
            } else {
                @Suppress("DEPRECATION") v.vibrate(ms)
            }
        }
    }
}

/** Одна отмеченная точка. */
data class Find(
    val time: Long,
    val level: Int,
    val lat: Double?,
    val lon: Double?,
    /** true — флажок поставлен автоматически по превышению порога на шкале. */
    val auto: Boolean = false,
    val note: String = "",
) {
    fun stamp(): String =
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(time))

    fun place(): String =
        if (lat == null || lon == null) "без координат"
        else String.format(Locale.US, "%.5f, %.5f", lat, lon)
}

/**
 * Журнал находок.
 *
 * Хранится в приватной папке приложения простым JSON — для такого объёма база
 * не нужна, а файл можно снять с телефона как есть.
 *
 * Список отдаётся потоком: карта и экран находок обновляются сами, как только
 * автоматика поставила новый флажок.
 */
class FindLog(private val ctx: Context) {

    private val file get() = ctx.filesDir.resolve("finds.json")

    private val _items = MutableStateFlow<List<Find>>(emptyList())
    val items: StateFlow<List<Find>> = _items

    init { _items.value = read() }

    private fun read(): List<Find> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Find(
                time = o.getLong("t"),
                level = o.getInt("l"),
                lat = if (o.isNull("lat")) null else o.getDouble("lat"),
                lon = if (o.isNull("lon")) null else o.getDouble("lon"),
                auto = o.optBoolean("a", false),
                note = o.optString("n", ""),
            )
        }.sortedByDescending { it.time }
    }.getOrDefault(emptyList())

    private fun write(list: List<Find>) = runCatching {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(JSONObject().apply {
                put("t", f.time); put("l", f.level)
                if (f.lat != null) put("lat", f.lat) else put("lat", JSONObject.NULL)
                if (f.lon != null) put("lon", f.lon) else put("lon", JSONObject.NULL)
                put("a", f.auto)
                put("n", f.note)
            })
        }
        file.writeText(arr.toString())
    }

    /** Добавить точку. Координаты передаёт вызывающий — у него они свежее. */
    fun add(level: Int, lat: Double?, lon: Double?, auto: Boolean = false): Find {
        val f = Find(System.currentTimeMillis(), level, lat, lon, auto)
        val list = listOf(f) + _items.value
        _items.value = list
        write(list)
        return f
    }

    /** Есть ли уже флажок ближе meters метров — чтобы не сыпать точки в кучу. */
    fun hasFlagNear(lat: Double, lon: Double, meters: Double): Boolean =
        _items.value.any { f ->
            f.lat != null && f.lon != null && distanceM(lat, lon, f.lat, f.lon) < meters
        }

    fun clear() {
        _items.value = emptyList()
        runCatching { file.delete() }
    }

    /** Всё в GPX, чтобы трек открылся в любом картографическом приложении. */
    fun toGpx(): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="T/S">""").append('\n')
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        _items.value.filter { it.lat != null && it.lon != null }.forEach { f ->
            append("""  <wpt lat="${f.lat}" lon="${f.lon}">""").append('\n')
            append("""    <time>${iso.format(Date(f.time))}</time>""").append('\n')
            append("""    <name>Цель ${f.level}</name>""").append('\n')
            append("  </wpt>").append('\n')
        }
        append("</gpx>")
    }

    companion object {
        /** Расстояние в метрах. Формула плоская — на десятках метров точна. */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dy = (lat2 - lat1) * 110_540.0
            val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
            return sqrt(dx * dx + dy * dy)
        }
    }
}
