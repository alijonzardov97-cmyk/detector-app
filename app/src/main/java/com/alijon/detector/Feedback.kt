package com.alijon.detector

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationAttributes
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
class VcoAudio(private val ctx: Context? = null) {

    var enabled: Boolean = false
        set(v) { field = v; if (v) start() else stop() }

    /** 0..levels, приходит из телеметрии. */
    @Volatile var level: Float = 0f
    @Volatile var levels: Int = 8
    /** 0..1 */
    @Volatile var volume: Float = 0.7f

    private var track: AudioTrack? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    private val thresholdHz = 240f      // фон, слышен всегда
    private val topHz = 1150f           // сильная цель

    /*
     * Скольжение частоты несимметричное, и это принципиально.
     *
     * Раньше было одно значение 0.12 на подъём и на спад. Подъём при этом
     * размазывался почти на 200 мс — цель уже под катушкой, а тон только
     * начинает ползти вверх. Ощущается это как «прибор опаздывает».
     *
     * Настоящий детектор звучит иначе: резкая атака и мягкий хвост. Поэтому
     * вверх идём почти мгновенно, вниз — плавно.
     */
    private val attack = 0.55f          // TUNE: подъём тона, 1.0 = мгновенно
    private val release = 0.10f         // TUNE: спад тона

    /*
     * Задержка звука на Android — это прежде всего размер буфера AudioTrack.
     * Прошлая версия брала minBufferSize и удваивала его на нестандартной
     * частоте 22050. Получалось около 150 мс уже записанного, но ещё не
     * прозвучавшего сигнала, да ещё через программный передискретизатор.
     *
     * Берём НАТИВНУЮ частоту устройства и нативный размер пачки — только на
     * них Android пускает дорожку по быстрому тракту. Плюс явный режим низкой
     * задержки. Это самый крупный выигрыш во всей цепочке.
     */
    private fun nativeRate(): Int {
        val am = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 48000
        return am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    }

    private fun nativeFrames(): Int {
        val am = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 256
        return am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
    }

    private fun start() {
        if (running) return
        running = true

        val rate = nativeRate()
        val frames = nativeFrames().coerceIn(64, 1024)
        val minBuf = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frames * 2)

        // Две пачки — минимум, при котором звук не рвётся, и это уже единицы
        // миллисекунд вместо сотни.
        val bufBytes = maxOf(minBuf, frames * 2 * 2)

        track = runCatching {
            AudioTrack.Builder()
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
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                .build()
                .also { it.play() }
        }.getOrNull()

        if (track == null) { running = false; return }

        worker = Thread {
            val buf = ShortArray(frames)
            var phase = 0.0
            var freq = thresholdHz
            var amp = 0.10f
            while (running) {
                val t = (level / levels.coerceAtLeast(1)).coerceIn(0f, 1f)
                val wantFreq = thresholdHz + (topHz - thresholdHz) * t
                val wantAmp = 0.10f + 0.90f * t          // фон тихий, цель громкая
                val kF = if (wantFreq > freq) attack else release
                val kA = if (wantAmp > amp) attack else release
                for (i in buf.indices) {
                    freq += (wantFreq - freq) * kF / buf.size
                    amp += (wantAmp - amp) * kA / buf.size
                    phase += 2.0 * PI * freq / rate
                    if (phase > 2 * PI) phase -= 2 * PI
                    buf[i] = (sin(phase) * amp * volume * 26000).toInt().toShort()
                }
                // Поток живёт своей жизнью: если дорожку освободили между
                // проверкой и записью, исключение здесь уронило бы всё
                // приложение — оно ведь не на главном потоке.
                try { track?.write(buf, 0, buf.size) } catch (_: Throwable) { break }
            }
        }.also { it.isDaemon = true; it.priority = Thread.MAX_PRIORITY; it.start() }
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

    /** Есть ли вообще мотор. Если нет — переключатель незачем показывать. */
    val available: Boolean = vib?.hasVibrator() == true

    var enabled = false

    /** С какой ступени начинать отклик. Ниже — молчим, чтобы не дёргать зря. */
    var triggerLevel = 3

    private var lastLevel = 0

    /*
     * Отклик даём не только на пересечении порога, но и на КАЖДОМ подъёме
     * ступени выше него. Так рука чувствует, что цель ближе, а не один
     * единственный толчок на входе в зону.
     */
    fun onLevel(level: Int) {
        if (enabled && level >= triggerLevel && level > lastLevel) buzz(level)
        lastLevel = level
    }

    /** Заметный отклик при включении переключателя — сразу видно, что работает. */
    fun test() {
        buzz(triggerLevel + 2)
    }

    /*
     * Почему здесь атрибуты, а не голый vibrate(effect).
     *
     * Устаревший vibrate(VibrationEffect) отправляет вибрацию с назначением
     * USAGE_UNKNOWN. Начиная с Android 12 система такие вибрации глушит в
     * массе ситуаций — беззвучный режим, «не беспокоить», выключенный отклик
     * на касания, — и приложение при этом не получает никакой ошибки: метод
     * отрабатывает, телефон молчит. Именно поэтому вибрация «не работала».
     *
     * Указываем назначение явно: на Android 13+ через VibrationAttributes,
     * на 8..12 — через AudioAttributes с типом SONIFICATION. Тогда вибрация
     * относится к полезному сигналу приложения и не подавляется.
     */
    private fun buzz(level: Int) {
        val v = vib ?: return
        if (!available) return

        // Значения зажимаем: createOneShot бросает исключение на нулевой
        // длительности и на амплитуде вне 1..255, а level приходит из эфира.
        val safe = level.coerceIn(0, 32)
        val ms = (60L + safe * 14L).coerceIn(40L, 400L)
        val amp = (120 + safe * 16).coerceIn(1, 255)

        runCatching {
            val effect = VibrationEffect.createOneShot(ms, amp)
            when {
                Build.VERSION.SDK_INT >= 33 ->
                    v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
                else ->
                    @Suppress("DEPRECATION")
                    v.vibrate(
                        effect,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
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
        append("""<gpx version="1.1" creator="STT Defense">""").append('\n')
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
