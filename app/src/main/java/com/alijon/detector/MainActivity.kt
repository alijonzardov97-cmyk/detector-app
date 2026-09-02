package com.alijon.detector

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/* ------------------------------------------------------------------ настройка */

/** Репозиторий с прошивками: владелец/имя. */
private const val REPO = "alijon/pi-detector"

/* -------------------------------------------------------------------- палитра */

private val Ground = Color(0xFF14100D)
private val Panel = Color(0xFF1E1813)
private val Edge = Color(0xFF3A2F24)
private val EdgeSoft = Color(0xFF2C241C)
private val Ink = Color(0xFFECE3D4)
private val InkDim = Color(0xFFA1907A)
private val InkFaint = Color(0xFF6D6053)
private val Brass = Color(0xFFE8A33D)
private val Oxide = Color(0xFFF0552A)
private val Slate = Color(0xFF7D9BB0)
private val Ok = Color(0xFF84B06A)
private val Crit = Color(0xFFD8482E)

private enum class Screen { DEVICES, CONSOLE, FIRMWARE, FINDS }

class MainActivity : ComponentActivity() {

    private lateinit var ble: DetectorBle
    private lateinit var audio: VcoAudio
    private lateinit var haptics: Haptics
    private lateinit var finds: FindLog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = DetectorBle(this)
        audio = VcoAudio()
        haptics = Haptics(this)
        finds = FindLog(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ground, surface = Panel)) {
                App(
                    ble = ble, audio = audio, haptics = haptics, finds = finds,
                    onKeepAwake = { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audio.enabled = false
        ble.disconnect()
    }
}

/* ----------------------------------------------------------------- каркас */

@Composable
private fun App(
    ble: DetectorBle,
    audio: VcoAudio,
    haptics: Haptics,
    finds: FindLog,
    onKeepAwake: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.DEVICES) }
    var granted by remember { mutableStateOf(hasScanPermission(ctx)) }

    val link by ble.link.collectAsStateWithLifecycle()
    val found by ble.found.collectAsStateWithLifecycle()
    val ident by ble.identity.collectAsStateWithLifecycle()
    val tele by ble.telemetry.collectAsStateWithLifecycle()

    val model = Models.of(ident?.model)
    val history = remember { mutableStateListOf<Int>().apply { repeat(160) { add(0) } } }

    // Телеметрия питает график, звук и вибрацию
    LaunchedEffect(tele.level) {
        history.removeAt(0); history.add(tele.level)
        audio.level = tele.level.toFloat()
        audio.levels = model.levels
        haptics.onLevel(tele.level)
    }
    LaunchedEffect(ident) { if (ident != null) screen = Screen.CONSOLE }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasScanPermission(ctx) }

    Column(
        Modifier.fillMaxSize().background(Ground)
            .padding(horizontal = 14.dp).verticalScroll(rememberScrollState())
    ) {
        TopBar(
            title = when (screen) {
                Screen.DEVICES -> "Приборы"
                Screen.CONSOLE -> model.title
                Screen.FIRMWARE -> "Прошивка"
                Screen.FINDS -> "Находки"
            },
            status = when (link) {
                Link.READY -> "На связи" to Ok
                Link.CONNECTING -> "Подключаюсь" to Brass
                Link.SCANNING -> "Поиск" to Brass
                Link.IDLE -> "Не подключён" to InkDim
            }
        )

        when (screen) {
            Screen.DEVICES -> DevicesScreen(
                found = found, scanning = link == Link.SCANNING, granted = granted,
                onGrant = { permLauncher.launch(scanPermissions()) },
                onScan = { if (link == Link.SCANNING) ble.stopScan() else ble.startScan() },
                onPick = { ble.connect(it.address) },
            )

            Screen.CONSOLE -> ConsoleScreen(
                model = model, tele = tele, history = history, audio = audio, haptics = haptics,
                onKeepAwake = onKeepAwake,
                onSetpoint = { key, v -> ble.send("$key$v") },
                onMarkFind = { finds.add(tele.level) },
                onFirmware = { screen = Screen.FIRMWARE },
                onFinds = { screen = Screen.FINDS },
                onBack = { ble.disconnect(); screen = Screen.DEVICES },
            )

            Screen.FIRMWARE -> FirmwareScreen(
                ident = ident, ble = ble, scope = scope,
                onBack = { screen = Screen.CONSOLE },
            )

            Screen.FINDS -> FindsScreen(
                log = finds, onBack = { screen = Screen.CONSOLE },
            )
        }
        Spacer(Modifier.height(28.dp))
    }
}

/* ------------------------------------------------------------------ шапка */

@Composable
private fun TopBar(title: String, status: Pair<String, Color>) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title.uppercase(), color = Ink, fontSize = 20.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            status.first, color = status.second, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.border(1.dp, Edge, RoundedCornerShape(2.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
    HorizontalDivider(color = EdgeSoft)
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) =
    Text(
        text.uppercase(), color = InkFaint, fontSize = 10.sp,
        fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp, modifier = modifier
    )

@Composable
private fun Section(content: @Composable ColumnScope.() -> Unit) =
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp)
            .background(Panel, RoundedCornerShape(3.dp))
            .border(1.dp, EdgeSoft, RoundedCornerShape(3.dp))
            .padding(14.dp),
        content = content
    )

/* -------------------------------------------------------------- приборы */

@Composable
private fun DevicesScreen(
    found: List<Found>, scanning: Boolean, granted: Boolean,
    onGrant: () -> Unit, onScan: () -> Unit, onPick: (Found) -> Unit,
) {
    if (!granted) {
        Section {
            Text("Нужно разрешение на поиск Bluetooth-устройств", color = InkDim, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Brass)) {
                Text("Разрешить", color = Ground)
            }
        }
        return
    }

    if (found.isEmpty()) {
        Section {
            Text(
                if (scanning) "Ищу приборы…"
                else "Приборов рядом нет. Включите прибор и нажмите «Искать приборы».",
                color = InkDim, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    found.forEach { d ->
        val m = Models.of(d.model)
        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp)
                .background(Panel, RoundedCornerShape(3.dp))
                .border(1.dp, EdgeSoft, RoundedCornerShape(3.dp))
                .clickable { onPick(d) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(34.dp).background(Edge, RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center
            ) { Text(m.title.take(3), color = Brass, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name ?: m.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(m.subtitle, d.serial?.let { "№ $it" }, d.fw?.let { "ПО $it" })
                        .joinToString(" · "),
                    color = InkFaint, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace
                )
            }
            Text("${d.rssi}", color = InkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }

    Spacer(Modifier.height(13.dp))
    Button(
        onClick = onScan, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = if (scanning) Edge else Brass)
    ) { Text(if (scanning) "Остановить" else "Искать приборы", color = if (scanning) Ink else Ground) }
}

/* -------------------------------------------------------------- консоль */

@Composable
private fun ConsoleScreen(
    model: Model, tele: Telemetry, history: List<Int>,
    audio: VcoAudio, haptics: Haptics,
    onKeepAwake: (Boolean) -> Unit,
    onSetpoint: (String, Int) -> Unit,
    onMarkFind: () -> Find,
    onFirmware: () -> Unit, onFinds: () -> Unit, onBack: () -> Unit,
) {
    var sound by remember { mutableStateOf(false) }
    var buzz by remember { mutableStateOf(true) }
    var awake by remember { mutableStateOf(true) }
    var lastFind by remember { mutableStateOf<Find?>(null) }

    LaunchedEffect(sound) { audio.enabled = sound }
    LaunchedEffect(buzz) { haptics.enabled = buzz }
    LaunchedEffect(awake) { onKeepAwake(awake) }

    Section {
        Gauge(tele.level, model.levels, Modifier.fillMaxWidth().height(140.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom) {
            Text(
                "${tele.level}", color = if (tele.level >= model.levels - 1) Oxide else Brass,
                fontSize = 58.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(9.dp))
            Label("из ${model.levels} · ступень", Modifier.padding(bottom = 12.dp))
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = EdgeSoft)
        Spacer(Modifier.height(8.dp))
        Label("Проводка · последние 8 с")
        Trace(history, model.levels, Modifier.fillMaxWidth().height(78.dp).padding(top = 6.dp))
    }

    model.controls.forEach { c ->
        val value = tele.setpoints[c.key] ?: ((c.max * 0.7f).toInt())
        var local by remember(c.key) { mutableStateOf(value.toFloat()) }
        LaunchedEffect(value) { if (local.toInt() != value) local = value.toFloat() }
        Section {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Label(c.name, Modifier.weight(1f))
                Text(
                    "${local.toInt()} / ${c.max}", color = InkFaint,
                    fontSize = 10.5.sp, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${(local / c.max * 100).toInt()} %", color = Ink,
                    fontSize = 18.sp, fontFamily = FontFamily.Monospace
                )
            }
            Slider(
                value = local, onValueChange = { local = it },
                onValueChangeFinished = { onSetpoint(c.key, local.toInt()) },
                valueRange = c.min.toFloat()..c.max.toFloat(),
                colors = SliderDefaults.colors(thumbColor = Brass, activeTrackColor = Brass,
                    inactiveTrackColor = Edge)
            )
        }
    }

    Section {
        Row(Modifier.fillMaxWidth()) {
            Stat("Аккумулятор", "%.2f В".format(tele.volts),
                "${tele.batteryPct} %", if (tele.batteryPct <= 10) Crit else Slate, Modifier.weight(1f))
            Stat("Температура", "${tele.tempC} °C",
                if (tele.heaterDuty > 0) "подогрев ${tele.heaterDuty * 100 / 255} %" else "подогрев выкл.",
                InkDim, Modifier.weight(1f))
            Stat("Питание", if (tele.charging) "зарядка" else "от батареи",
                if (tele.charging) "USB подключён" else "USB не подключён",
                if (tele.charging) Ok else InkDim, Modifier.weight(1f))
        }
    }

    Section {
        Toggle("Звук в телефоне", sound) { sound = it }
        Toggle("Вибрация на цель", buzz) { buzz = it }
        Toggle("Не гасить экран", awake) { awake = it }
    }

    Spacer(Modifier.height(13.dp))
    Button(
        onClick = { lastFind = onMarkFind() }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Brass)
    ) { Text("Отметить находку", color = Ground) }
    lastFind?.let {
        Text(
            "Записано: ${it.stamp()} · ${it.place()}", color = InkFaint,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp)
        )
    }

    Spacer(Modifier.height(9.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("К приборам", color = InkDim) }
        OutlinedButton(onClick = onFinds, modifier = Modifier.weight(1f)) { Text("Находки", color = InkDim) }
        OutlinedButton(onClick = onFirmware, modifier = Modifier.weight(1f)) { Text("Прошивка", color = Brass) }
    }
}

@Composable
private fun Stat(label: String, value: String, sub: String, tint: Color, modifier: Modifier) =
    Column(modifier.padding(end = 8.dp)) {
        Label(label)
        Spacer(Modifier.height(4.dp))
        Text(value, color = tint, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        Text(sub, color = InkFaint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Brass, checkedTrackColor = Edge)
        )
    }

/* --------------------------------------------------------------- графика */

@Composable
private fun Gauge(level: Int, levels: Int, modifier: Modifier) = Canvas(modifier) {
    val cx = size.width / 2f
    val cy = size.height * 0.94f
    val r = minOf(size.width * 0.46f, size.height * 0.88f)
    val thick = r * 0.20f
    val gapDeg = 1.8f
    val spanDeg = (180f - gapDeg * (levels - 1)) / levels

    for (i in 0 until levels) {
        val start = 180f + i * (spanDeg + gapDeg)
        drawArc(
            color = when {
                i >= level -> EdgeSoft
                i >= levels - 2 -> Oxide
                else -> Brass
            },
            startAngle = start, sweepAngle = spanDeg, useCenter = false,
            topLeft = Offset(cx - r + thick / 2, cy - r + thick / 2),
            size = Size((r - thick / 2) * 2, (r - thick / 2) * 2),
            style = Stroke(width = thick)
        )
    }
    drawLine(EdgeSoft, Offset(cx - r - 8, cy), Offset(cx + r + 8, cy), 1f)
}

@Composable
private fun Trace(history: List<Int>, levels: Int, modifier: Modifier) = Canvas(modifier) {
    if (history.isEmpty()) return@Canvas
    val pad = 3f
    fun y(v: Int) = size.height - pad - (v.toFloat() / levels) * (size.height - pad * 2)
    fun x(i: Int) = i.toFloat() / (history.size - 1) * size.width

    listOf(2, 4, 6).forEach { drawLine(EdgeSoft, Offset(0f, y(it)), Offset(size.width, y(it)), 1f) }

    val fill = Path().apply {
        moveTo(0f, size.height)
        history.forEachIndexed { i, v -> lineTo(x(i), y(v)) }
        lineTo(size.width, size.height); close()
    }
    drawPath(fill, Brass.copy(alpha = 0.22f))

    val line = Path().apply {
        history.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
    }
    drawPath(line, Brass, style = Stroke(width = 2f))

    val last = history.last()
    drawCircle(if (last >= levels - 1) Oxide else Brass, 3.5f, Offset(size.width - 2f, y(last)))
}

/* -------------------------------------------------------------- прошивка */

@Composable
private fun FirmwareScreen(
    ident: Identity?, ble: DetectorBle,
    scope: kotlinx.coroutines.CoroutineScope, onBack: () -> Unit,
) {
    val fw = remember { Firmware(REPO) }
    var latest by remember { mutableStateOf<FirmwareBuild?>(null) }
    var note by remember { mutableStateOf("нажмите «Проверить»") }
    var progress by remember { mutableStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }

    Section {
        InfoRow("Модель", Models.of(ident?.model).title)
        InfoRow("Серийный номер", ident?.serial ?: "—")
        InfoRow("Версия в приборе", ident?.fw ?: "—")
        InfoRow("Доступна", note)

        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { progress }, modifier = Modifier.fillMaxWidth().height(6.dp),
            color = Brass, trackColor = Edge
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton(
                enabled = !busy && ident != null, modifier = Modifier.weight(1f),
                onClick = {
                    scope.launch {
                        note = "проверяю…"
                        val b = runCatching { fw.latestFor(ident!!.model) }.getOrNull()
                        latest = b
                        note = when {
                            b == null -> "нет сборки для этой модели"
                            Firmware.compare(b.version, ident!!.fw) > 0 -> "${b.version} · новее"
                            else -> "${b.version} · уже стоит"
                        }
                        b?.let { logs.add(0, "Найдена ${it.model}-${it.version}.bin, ${it.size / 1024} КБ") }
                    }
                }
            ) { Text("Проверить", color = InkDim) }

            Button(
                enabled = !busy && latest != null && ident != null &&
                        Firmware.compare(latest!!.version, ident!!.fw) > 0,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Brass),
                onClick = {
                    scope.launch {
                        busy = true; progress = 0f
                        logs.add(0, "Скачиваю прошивку…")
                        val image = runCatching { fw.download(latest!!) }.getOrNull()
                        if (image == null) { logs.add(0, "Не удалось скачать файл"); busy = false; return@launch }
                        logs.add(0, "Получено ${image.size} байт, передаю в прибор")
                        val err = ble.sendFirmware(image) { progress = it }
                        logs.add(0, err?.let { "Прервано: $it. Прибор остался на прежней прошивке." }
                            ?: "Готово. Прибор перезагружается на ${latest!!.version}")
                        busy = false
                    }
                }
            ) { Text("Обновить", color = Ground) }
        }

        if (logs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = EdgeSoft)
            Spacer(Modifier.height(8.dp))
            logs.take(6).forEach {
                Text(it, color = InkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        "Питание при обновлении не отключайте. Пока новый образ не записан целиком, " +
                "прибор работает на старом — обрыв придётся начинать заново.",
        color = InkDim, fontSize = 12.sp
    )
    Spacer(Modifier.height(13.dp))
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text("Назад к прибору", color = InkDim)
    }
}

@Composable
private fun InfoRow(label: String, value: String) =
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Label(label, Modifier.weight(1f))
        Text(value, color = Ink, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }

/* --------------------------------------------------------------- находки */

@Composable
private fun FindsScreen(log: FindLog, onBack: () -> Unit) {
    var items by remember { mutableStateOf(log.load()) }

    if (items.isEmpty()) {
        Section {
            Text(
                "Пока пусто. На экране прибора нажмите «Отметить находку» — " +
                        "запишется время, ступень отклика и координаты.",
                color = InkDim, fontSize = 13.sp
            )
        }
    }

    items.forEach { f ->
        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp)
                .background(Panel, RoundedCornerShape(3.dp))
                .border(1.dp, EdgeSoft, RoundedCornerShape(3.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(30.dp).background(Edge, RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center
            ) { Text("${f.level}", color = Brass, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.stamp(), color = Ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Text(f.place(), color = InkFaint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }

    Spacer(Modifier.height(13.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Назад", color = InkDim) }
        OutlinedButton(
            onClick = { log.clear(); items = emptyList() }, modifier = Modifier.weight(1f)
        ) { Text("Очистить", color = Crit) }
    }
}

/* ------------------------------------------------------------ разрешения */

private fun scanPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,   // нужен для координат находок
        )
    else
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasScanPermission(ctx: android.content.Context): Boolean {
    val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    return needed.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
}
