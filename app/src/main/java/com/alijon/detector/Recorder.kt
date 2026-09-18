package com.alijon.detector

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ===========================================================================
 *  Запись прохода
 * ===========================================================================
 *
 *  ЗАЧЕМ.
 *
 *  Два захода на улучшение поиска целей провалились одинаково: алгоритм
 *  проверялся на придуманных профилях сигнала и на них выигрывал, а на живом
 *  приборе проигрывал. Придуманный профиль — это догадка о форме сигнала, а не
 *  сигнал. Пока нет записи с прибора, любой следующий алгоритм будет такой же
 *  лотереей.
 *
 *  ЧТО ПИШЕТСЯ.
 *
 *  Строки телеметрии КАК ЕСТЬ, без разбора и без обработки. Ни сглаживания, ни
 *  округления, ни отбрасывания «лишних» полей: запись должна давать возможность
 *  повторить ровно то, что видело приложение, включая его собственные ошибки.
 *  К каждой строке приписано время её прихода от начала записи — по нему видно
 *  и дрожание эфира, и пропажи связи, чего в самой строке нет.
 *
 *  В шапку кладутся версии и настройки: без них через неделю уже не вспомнить,
 *  на какой глубине провала и на каком размахе снят файл.
 *
 *  РАЗМЕР. Шестьдесят строк в секунду по сорок байт — полтора мегабайта на
 *  десять минут. Держим в памяти, пишем на диск один раз при остановке: так
 *  запись не мешает приёму и не роняет кадры.
 */
class SweepRecorder(private val ctx: Context) {

    /** Идёт ли запись. Читает кнопка на экране. */
    var active by mutableStateOf(false)
        private set

    /** Сколько строк уже записано. Для подписи на кнопке. */
    var lines by mutableIntStateOf(0)
        private set

    /** Последний сохранённый файл — его предлагаем отправить. */
    var saved by mutableStateOf<File?>(null)
        private set

    private val buf = ArrayList<String>(4096)
    private var startedAt = 0L
    private var header: List<String> = emptyList()

    /**
     * Начать запись.
     *
     * Шапку собираем здесь, а не при сохранении: настройки к концу прохода
     * могли уже поменяться, а интересны те, при которых снято.
     */
    fun start(model: String, fw: String, serial: String, dip: Int, sweepSpeed: Int) {
        synchronized(buf) {
            buf.clear()
            header = listOf(
                "# STT Defense sweep log v1",
                "# app=${BuildConfig.VERSION_NAME} model=$model fw=$fw serial=$serial",
                "# dip=$dip sweep=$sweepSpeed (0=fast 1=mid 2=slow)",
                "# started=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                "# columns: <ms since start>\\t<raw telemetry line>",
            )
        }
        startedAt = SystemClock.elapsedRealtime()
        lines = 0
        saved = null
        active = true
    }

    /**
     * Принять строку. Зовётся из потока Bluetooth, поэтому здесь не должно
     * быть ничего тяжелее добавления в список.
     */
    fun feed(raw: String) {
        if (!active) return
        val dt = SystemClock.elapsedRealtime() - startedAt
        synchronized(buf) {
            if (buf.size >= CAP) { active = false; return }
            buf.add("$dt\t$raw")
        }
        lines = buf.size
    }

    /** Остановить и записать файл. null — писать было нечего. */
    fun stop(): File? {
        active = false
        val snapshot: List<String>
        synchronized(buf) {
            if (buf.isEmpty()) return null
            snapshot = header + buf
        }
        val dir = File(ctx.filesDir, "records").apply { mkdirs() }
        val name = "sweep-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val file = File(dir, name)
        return runCatching {
            file.writeText(snapshot.joinToString("\n"))
            saved = file
            file
        }.getOrNull()
    }

    private companion object {
        /** Предел на всякий случай: примерно полчаса непрерывной записи. */
        const val CAP = 120_000
    }
}
