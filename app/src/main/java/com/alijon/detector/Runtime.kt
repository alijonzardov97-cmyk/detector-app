package com.alijon.detector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import kotlin.math.log10
import kotlin.math.max

/* ===========================================================================
 *  Приёмник координат по факту находки
 * ===========================================================================
 */

/**
 * Держит GPS выключенным, пока металл не найден.
 *
 * Приёмник — самый прожорливый узел в телефоне, и держать его включённым всю
 * проводку значит посадить батарею за пару часов. Включаем по факту цели.
 *
 * Есть обратная сторона, и о ней надо знать: холодный старт приёмника занимает
 * десятки секунд. Поэтому после спада сигнала GPS гасится не сразу, а через
 * HOLD_MS — серия находок на одном участке не гоняет приёмник по кругу, и к
 * следующей цели он уже готов. Первый флажок в новом месте может оказаться без
 * координат: приёмник просто не успел взять небо. Приложение такую находку
 * всё равно запишет — со временем и ступенью.
 */
class GpsGate(private val tracker: LocationTracker) {

    /** Сколько держать приёмник включённым после того, как сигнал пропал. */
    private val holdMs = 90_000L

    /** С какой ступени считать, что найден металл. Ставится из настроек. */
    var armLevel: Int = AUTO_FLAG_LEVEL

    /** Экран карты просит держать приёмник включённым, пока он открыт. */
    var pinned: Boolean = false
        set(v) { field = v; if (v) tracker.start() }

    private var offAt = 0L

    /** Вызывать при каждом изменении ступени. */
    fun onLevel(level: Int) {
        if (level >= armLevel) {
            tracker.start()
            offAt = System.currentTimeMillis() + holdMs
        }
    }

    /** Вызывать раз в секунду: гасит приёмник, когда время удержания вышло. */
    fun tick() {
        if (pinned || offAt == 0L) return
        if (System.currentTimeMillis() >= offAt) {
            tracker.stop()
            offAt = 0L
        }
    }

    fun stop() { offAt = 0L; if (!pinned) tracker.stop() }
}

/* ===========================================================================
 *  Яркость экрана
 * ===========================================================================
 */

/**
 * Автоматическая яркость по датчику освещённости.
 *
 * Системная автояркость приложению не подчиняется, а на поиске это важно: на
 * солнце экран должен гореть в полную силу, в сумерках — почти гаснуть, чтобы
 * не слепить и не сажать батарею. Считаем по логарифму освещённости: глаз
 * воспринимает свет именно так, и линейная шкала давала бы резкие скачки
 * между тенью и солнцем.
 */
class BrightnessControl(ctx: Context) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val light: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)

    val available: Boolean = light != null

    /** Куда отдавать посчитанное значение 0..1. Ставит экран. */
    var onValue: ((Float) -> Unit)? = null

    private var running = false

    fun start() {
        if (running || light == null) return
        running = true
        sm?.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        if (!running) return
        running = false
        sm?.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val lux = max(1f, e.values.firstOrNull() ?: return)
        // 1 лк -> почти погашен, 10000 лк (солнце) -> максимум.
        val v = (log10(lux) / 4f).coerceIn(0.05f, 1f)
        onValue?.invoke(v)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/* ===========================================================================
 *  Отправка координат
 * ===========================================================================
 */

object Share {

    /**
     * Отдаёт координаты любому мессенджеру простым текстом.
     *
     * Именно текстом, а не geo-ссылкой: WhatsApp и Telegram текст принимают
     * всегда, а специальные схемы часть сборок молча отбрасывает. Ссылку на
     * карту вкладываем в тот же текст — получатель откроет её одним нажатием.
     */
    fun point(ctx: Context, lat: Double, lon: Double, caption: String) {
        val coords = String.format(java.util.Locale.ROOT, "%.6f, %.6f", lat, lon)
        val link = String.format(java.util.Locale.ROOT,
            "https://maps.google.com/?q=%.6f,%.6f", lat, lon)
        send(ctx, "$caption\n$coords\n$link")
    }

    /** Все точки списком — когда нужно передать целый участок. */
    fun points(ctx: Context, finds: List<Find>, caption: String) {
        val body = finds.filter { it.lat != null && it.lon != null }.joinToString("\n") { f ->
            String.format(java.util.Locale.ROOT, "%s  %.6f, %.6f", f.stamp(), f.lat, f.lon)
        }
        if (body.isBlank()) return
        send(ctx, "$caption\n$body")
    }

    private fun send(ctx: Context, text: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        // Выбор приложения показываем всегда: иначе система запоминает первый
        // мессенджер и отправляет туда молча.
        val chooser = Intent.createChooser(i, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(chooser) }
    }
}

/* ===========================================================================
 *  Работа при погашенном экране
 * ===========================================================================
 */

/**
 * Служба переднего плана.
 *
 * Сама она ничего не делает — и это правильно. Её единственная задача:
 * показать уведомление, из-за которого система считает процесс нужным и не
 * усыпляет его. Без этого Android через несколько минут после гашения экрана
 * режет и Bluetooth-соединение, и звуковой поток, а приложение об этом даже
 * не узнаёт: оно «работает», но прибор молчит.
 */
class DetectorService : Service() {

    private var wake: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = "work"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(ch, "STT Defense", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val n: Notification = Notification.Builder(this, ch)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(S.bgRunning.t)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        startForeground(1, n)

        /*
         * Частичная блокировка сна.
         *
         * Служба переднего плана сама по себе не мешает процессору уснуть
         * вместе с экраном: она лишь защищает от выгрузки. Поток, который
         * считает тон, при засыпании встаёт, и звук обрывается через
         * несколько секунд после гашения экрана. Блокировка держит только
         * процессор — экран и клавиши остаются погашенными.
         */
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "sttdefense:field")
                .also { it.setReferenceCounted(false); it.acquire() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Блокировку обязательно снимаем: забытая держит процессор бодрым до
        // самой перезагрузки телефона и съедает батарею вернее любого GPS.
        runCatching { wake?.let { if (it.isHeld) it.release() } }
        wake = null
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, DetectorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, DetectorService::class.java)) }
        }
    }
}
