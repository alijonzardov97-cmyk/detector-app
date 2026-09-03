package com.alijon.detector

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/* ===========================================================================
 *  Автоматические флажки
 * ===========================================================================
 *
 *  Правило простое: как только на шкале становится больше AUTO_FLAG_LEVEL
 *  делений, ставим флажок и отдельно сохраняем координаты.
 *
 *  Два ограничителя обязательны, иначе одна проводка над крупной целью
 *  насыпет десятки точек в одно место:
 *    - флажок ставится только на ПЕРЕСЕЧЕНИИ порога снизу вверх, а не всё
 *      время, пока сигнал держится;
 *    - между флажками должно пройти AUTO_FLAG_MIN_MS и набежать
 *      AUTO_FLAG_MIN_M метров от предыдущей точки.
 */
const val AUTO_FLAG_LEVEL = 4          // «больше 4 делений»
const val AUTO_FLAG_MIN_MS = 4000L     // не чаще раза в 4 секунды
const val AUTO_FLAG_MIN_M = 3.0        // и не ближе 3 метров к прошлому флажку

class AutoFlagger(
    private val log: FindLog,
    private val tracker: LocationTracker,
) {
    var enabled: Boolean = true
    var threshold: Int = AUTO_FLAG_LEVEL

    private var wasAbove = false
    private var lastMs = 0L

    fun onLevel(level: Int) {
        val above = level > threshold
        if (enabled && above && !wasAbove) place(level)
        wasAbove = above
    }

    private fun place(level: Int) {
        val now = System.currentTimeMillis()
        if (now - lastMs < AUTO_FLAG_MIN_MS) return

        val here = tracker.position.value
        // Без координат флажок ставить некуда, но находку всё равно
        // записываем — со временем и уровнем, чтобы не потерять факт.
        if (here != null && log.hasFlagNear(here.latitude, here.longitude, AUTO_FLAG_MIN_M)) return

        log.add(level, here?.latitude, here?.longitude, auto = true)
        lastMs = now
    }
}

/* ===========================================================================
 *  Текущее положение
 * ===========================================================================
 *
 *  Отдельный источник координат: FindLog.add() раньше брал последнюю
 *  известную точку, а она в поле бывает получасовой давности. Для карты и
 *  для автофлажков нужен живой поток.
 */
class LocationTracker(private val ctx: Context) {

    val position = MutableStateFlow<Location?>(null)

    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var running = false

    /*
     * Слушателя пишем полным объектом, а не лямбдой. У LocationListener в
     * новых Android остальные методы имеют реализацию по умолчанию, но на
     * Android 8 их нет — SAM-лямбда там свалилась бы в AbstractMethodError.
     */
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { position.value = location }
        @Deprecated("нужен для Android 8")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun granted(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (running || !granted()) return
        val m = lm ?: return
        running = true
        runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { m.isProviderEnabled(it) }
                .forEach { m.requestLocationUpdates(it, 1000L, 1f, listener) }
            // Пока не пришло первое обновление — показываем хоть что-то.
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { m.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
                ?.let { if (position.value == null) position.value = it }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { lm?.removeUpdates(listener) }
    }
}

/* ===========================================================================
 *  Экран карты
 * =========================================================================== */

/** Один раз настроить osmdroid: кэш внутри приложения, без прав на хранилище. */
private fun configureOsmdroid(ctx: Context) {
    val cfg = Configuration.getInstance()
    runCatching {
        cfg.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        // Сервера OSM отдают тайлы только приложениям с внятным User-Agent.
        cfg.userAgentValue = ctx.packageName
        cfg.osmdroidBasePath = File(ctx.filesDir, "osmdroid").apply { mkdirs() }
        cfg.osmdroidTileCache = File(ctx.filesDir, "osmdroid/tiles").apply { mkdirs() }
    }
}

@Composable
fun MapScreen(
    log: FindLog,
    tracker: LocationTracker,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val finds by log.items.collectAsStateWithLifecycle()
    val here by tracker.position.collectAsStateWithLifecycle()

    var tiles by remember { mutableStateOf(true) }   // карта или автономная схема
    var confirmClear by remember { mutableStateOf(false) }

    // Слушаем координаты только пока экран открыт — иначе сажаем батарею.
    DisposableEffect(Unit) {
        tracker.start()
        onDispose { }        // остановкой управляет MainActivity
    }

    // Кнопки — заливкой, а не тонким контуром: поверх карты и на тёмном фоне
    // контурная кнопка почти не читается.
    Column(modifier.fillMaxWidth()) {

        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Button(
                onClick = { tiles = !tiles }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Ink),
            ) { Text(if (tiles) "Схема" else "Карта", fontSize = 13.sp) }

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = { confirmClear = true },
                enabled = finds.isNotEmpty(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Panel, contentColor = Oxide,
                    disabledContainerColor = Panel, disabledContentColor = InkFaint,
                ),
            ) { Text("Стереть флажки", fontSize = 13.sp) }
        }

        // Карта занимает остаток высоты — кнопки сверху и снизу всегда на виду.
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .background(Panel, RoundedCornerShape(10.dp))
                .border(1.dp, Edge, RoundedCornerShape(10.dp))
        ) {
            if (tiles) OsmMap(finds, here) else OfflinePlot(finds, here)
        }

        val withCoords = finds.count { it.lat != null }
        Text(
            "Флажков: $withCoords" + if (finds.size > withCoords) "  ·  без координат: ${finds.size - withCoords}" else "",
            color = InkDim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
        )
        if (!tracker.granted()) {
            Text(
                "Нет доступа к геолокации — флажки ставиться не будут.",
                color = Oxide, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onBack, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Ink),
        ) { Text("Назад") }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Panel,
            title = { Text("Стереть все флажки?", color = Ink) },
            text = { Text("Будут удалены все ${finds.size} точек вместе с координатами. Отменить нельзя.", color = InkDim) },
            confirmButton = {
                Button(
                    onClick = { log.clear(); confirmClear = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Oxide),
                ) { Text("Стереть", color = Ground) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Отмена", color = InkDim) }
            },
        )
    }
}

/* ------------------------------------------------------------ карта OSM */

@Composable
private fun OsmMap(finds: List<Find>, here: Location?) {
    val ctx = LocalContext.current
    remember { configureOsmdroid(ctx); true }

    val flag = remember { ContextCompat.getDrawable(ctx, R.drawable.ic_find_flag) }
    val dot = remember { ContextCompat.getDrawable(ctx, R.drawable.ic_here) }
    var centred by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { c ->
            MapView(c).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                setUseDataConnection(true)
                controller.setZoom(18.0)
                onResume()
            }
        },
        update = { map ->
            map.overlays.clear()

            finds.filter { it.lat != null && it.lon != null }.forEach { f ->
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(f.lat!!, f.lon!!)
                    setAnchor(Marker.ANCHOR_LEFT, Marker.ANCHOR_BOTTOM)
                    title = "${f.stamp()} · ступень ${f.level}"
                    flag?.let { icon = it }
                })
            }

            here?.let { h ->
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(h.latitude, h.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Вы здесь"
                    dot?.let { icon = it }
                })
                // Центрируем один раз, дальше карту двигает человек.
                if (!centred) {
                    map.controller.setCenter(GeoPoint(h.latitude, h.longitude))
                    centred = true
                }
            }
            map.invalidate()
        },
        onRelease = { map -> runCatching { map.onPause(); map.onDetach() } },
    )
}

/* -------------------------------------------------- автономная схема */

/**
 * Рисуем сами, без тайлов: своя точка в центре, флажки вокруг, сетка с
 * подписью масштаба. Работает всегда — в поле без сети это единственное,
 * что точно покажет картинку.
 */
@Composable
private fun OfflinePlot(finds: List<Find>, here: Location?) {
    val pts = finds.filter { it.lat != null && it.lon != null }

    if (pts.isEmpty() && here == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Нет ни одной точки с координатами", color = InkFaint, fontSize = 13.sp)
        }
        return
    }

    // Центр — своя точка, иначе самый свежий флажок.
    val lat0 = here?.latitude ?: pts.first().lat!!
    val lon0 = here?.longitude ?: pts.first().lon!!
    val kx = 111_320.0 * cos(Math.toRadians(lat0))   // метров в градусе долготы
    val ky = 110_540.0                               // метров в градусе широты

    data class P(val x: Double, val y: Double, val level: Int)
    val local = pts.map { P((it.lon!! - lon0) * kx, (it.lat!! - lat0) * ky, it.level) }

    // Половина стороны видимой области в метрах: минимум 20 м, иначе по данным.
    val span = max(20.0, local.maxOfOrNull { max(abs(it.x), abs(it.y)) }?.times(1.25) ?: 20.0)

    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(8.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = (minOf(size.width, size.height) / 2f) / span.toFloat()

        // сетка с шагом, кратным «круглым» метрам
        val step = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
            .firstOrNull { span / it <= 5 } ?: 1000.0
        var r = step
        while (r <= span) {
            val rad = (r * scale).toFloat()
            drawCircle(EdgeSoft, rad, Offset(cx, cy), style = Stroke(width = 1f))
            r += step
        }
        drawLine(EdgeSoft, Offset(0f, cy), Offset(size.width, cy), 1f)
        drawLine(EdgeSoft, Offset(cx, 0f), Offset(cx, size.height), 1f)

        // флажки
        local.forEach { p ->
            val x = cx + (p.x * scale).toFloat()
            val y = cy - (p.y * scale).toFloat()
            drawCircle(if (p.level >= 7) Oxide else Brass, 5f, Offset(x, y))
            val staff = Path().apply { moveTo(x, y); lineTo(x, y - 14f) }
            drawPath(staff, Ink, style = Stroke(width = 2f))
            val cloth = Path().apply {
                moveTo(x, y - 14f); lineTo(x + 10f, y - 10f); lineTo(x, y - 6f); close()
            }
            drawPath(cloth, if (p.level >= 7) Oxide else Brass)
        }

        // своя точка
        if (here != null) {
            drawCircle(Slate, 7f, Offset(cx, cy))
            drawCircle(Ground, 4.5f, Offset(cx, cy))
            drawCircle(Ok, 2.5f, Offset(cx, cy))
        }

        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#A1907A")
                textSize = 26f
                isAntiAlias = true
            }
            drawText("кольцо = ${step.roundToInt()} м", 6f, size.height - 8f, paint)
        }
    }
}
