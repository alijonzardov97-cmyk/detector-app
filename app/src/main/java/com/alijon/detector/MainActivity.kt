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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/* ------------------------------------------------------------------ настройка */

/** Репозиторий с прошивками: владелец/имя. */
private const val REPO = "alijonzardov97-cmyk/detector-app"

/* -------------------------------------------------------------------- палитра */
// Цвета живут в Appearance.kt: они переключаются на ходу вместе с оформлением.

private enum class Screen { DEVICES, CONSOLE, FIRMWARE, FINDS, MAP, LOCK, SETTINGS }

class MainActivity : ComponentActivity() {

    private lateinit var ble: DetectorBle
    private lateinit var audio: VcoAudio
    private lateinit var haptics: Haptics
    private lateinit var finds: FindLog
    private lateinit var tracker: LocationTracker
    private lateinit var flagger: AutoFlagger
    private lateinit var look: Appearance
    private lateinit var lang: Localization

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        look = Appearance(this)          // до setContent: цвета уже нужные
        lang = Localization(this)        // и язык тоже
        ble = DetectorBle(this)
        audio = VcoAudio(this)
        haptics = Haptics(this)
        finds = FindLog(this)
        tracker = LocationTracker(this)
        flagger = AutoFlagger(finds, tracker)

        setContent {
            /*
             * Светлая тема требует своей цветовой схемы, иначе Material рисует
             * системные элементы (ползунки, переключатели, диалоги) тёмными на
             * светлом фоне. Ориентируемся на яркость выбранного фона.
             */
            val lightish = Ground.red + Ground.green + Ground.blue > 1.5f
            MaterialTheme(
                colorScheme =
                    if (lightish) lightColorScheme(background = Ground, surface = Panel)
                    else darkColorScheme(background = Ground, surface = Panel)
            ) {
                /*
                 * Арабский пишется справа налево, и это касается не только
                 * текста: полосы, ползунки и ряды кнопок тоже должны идти в
                 * обратную сторону. Подменяем направление на весь интерфейс.
                 */
                CompositionLocalProvider(
                    LocalLayoutDirection provides
                        if (CurrentLang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                App(
                    ble = ble, audio = audio, haptics = haptics, finds = finds,
                    tracker = tracker, flagger = flagger, look = look, lang = lang,
                    onKeepAwake = { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                )
                }
            }
        }
    }

    override fun onResume() { super.onResume(); tracker.start() }

    override fun onPause() { super.onPause(); tracker.stop() }

    override fun onDestroy() {
        super.onDestroy()
        audio.enabled = false
        tracker.stop()
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
    tracker: LocationTracker,
    flagger: AutoFlagger,
    look: Appearance,
    lang: Localization,
    onKeepAwake: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.DEVICES) }

    /*
     * Переключатели живут ЗДЕСЬ, а не внутри экрана прибора.
     *
     * Раньше они были remember-состоянием ConsoleScreen. Стоило уйти на карту,
     * экран покидал композицию, remember забывался, и при возврате звук
     * оказывался выключен — LaunchedEffect срабатывал с исходным false и гасил
     * дорожку. Здесь состояние переживает любые переходы между экранами, а
     * rememberSaveable — ещё и поворот экрана.
     */
    var sound by rememberSaveable { mutableStateOf(false) }
    var buzz by rememberSaveable { mutableStateOf(true) }
    var awake by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(sound) { audio.enabled = sound }
    LaunchedEffect(buzz) { haptics.enabled = buzz }
    LaunchedEffect(awake) { onKeepAwake(awake) }
    var granted by remember { mutableStateOf(hasScanPermission(ctx)) }

    val link by ble.link.collectAsStateWithLifecycle()
    val found by ble.found.collectAsStateWithLifecycle()
    val ident by ble.identity.collectAsStateWithLifecycle()
    val tele by ble.telemetry.collectAsStateWithLifecycle()

    val model = Models.of(ident?.model)

    /*
     * График держим как ОБЫЧНЫЙ неизменяемый список внутри одного состояния,
     * а не как mutableStateListOf.
     *
     * Так было раньше и так делать нельзя: список правился из LaunchedEffect
     * (removeAt/add), а Canvas в это же время обходил его через forEachIndexed.
     * Снимок списка менялся прямо во время обхода — ConcurrentModificationException
     * и вылет приложения. Проявлялось это ровно при обнаружении цели: пока
     * ступень равна нулю, значение не меняется, эффект не срабатывает и список
     * никто не трогает.
     *
     * Замена целого списка одним присваиванием такой гонки не допускает в
     * принципе: тот экземпляр, который начал рисовать Canvas, уже никогда не
     * изменится.
     */
    val history = remember { mutableStateOf(List(TRACE_POINTS) { 0 }) }

    // График — равномерная выборка по времени. Раньше ключом эффекта была сама
    // ступень, поэтому точка добавлялась только при её изменении, и «время» на
    // графике шло рывками.
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(TRACE_STEP_MS)
            history.value = history.value.drop(1) + ble.telemetry.value.level
        }
    }

    // Звук и вибрация — наоборот, сразу по факту изменения ступени.
    LaunchedEffect(tele.level, model.levels) {
        audio.level = tele.level.toFloat()
        audio.levels = model.levels
        haptics.onLevel(tele.level)
        // Больше AUTO_FLAG_LEVEL делений — сам ставим флажок и пишем координаты.
        flagger.onLevel(tele.level)
    }
    LaunchedEffect(ident) { if (ident != null) screen = Screen.CONSOLE }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasScanPermission(ctx) }

    /*
     * Карта НЕ должна жить внутри прокручиваемой страницы.
     *
     * Так было раньше, и вот что получалось: MapView перехватывает
     * вертикальные жесты, поэтому пальцем по карте страницу не пролистать, а
     * кнопки под картой оказывались ниже края экрана — недоступны и не видны.
     * На экране карты прокрутку выключаем, и карта занимает ровно остаток
     * высоты между верхними и нижними кнопками.
     */
    val pageScroll = rememberScrollState()
    val scrollable = screen != Screen.MAP

    /*
     * ОТСТУПЫ ПОД СИСТЕМНЫЕ ПАНЕЛИ.
     *
     * targetSdk 35 — на Android 15 система разворачивает окно во весь экран
     * принудительно, и приложение рисует под строкой состояния и под панелью
     * навигации. Отсюда и «Не подключён» под значком батареи, и кнопки,
     * уезжающие под экранные клавиши телефона. safeDrawing даёт отступы разом
     * под обе панели и под вырез камеры, а фон при этом остаётся на весь экран.
     */
    Column(
        Modifier.fillMaxSize().background(Ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 14.dp)
            .then(if (scrollable) Modifier.verticalScroll(pageScroll) else Modifier)
    ) {
        TopBar(
            title = when (screen) {
                Screen.DEVICES -> S.navDevices.t
                Screen.CONSOLE -> model.title
                Screen.FIRMWARE -> S.navFirmware.t
                Screen.FINDS -> S.navFinds.t
                Screen.MAP -> S.navMap.t
                Screen.LOCK -> S.navAccess.t
                Screen.SETTINGS -> S.navSettings.t
            },
            status = when (link) {
                Link.READY -> S.stReady.t to Ok
                Link.CONNECTING -> S.stConnecting.t to Brass
                Link.SCANNING -> S.stScanning.t to Brass
                Link.IDLE -> S.stIdle.t to InkDim
            }
        )

        when (screen) {
            Screen.DEVICES -> DevicesScreen(
                found = found, scanning = link == Link.SCANNING, granted = granted,
                lang = lang,
                onGrant = { permLauncher.launch(scanPermissions()) },
                onScan = { if (link == Link.SCANNING) ble.stopScan() else ble.startScan() },
                onPick = { ble.connect(it.address) },
            )

            Screen.CONSOLE -> ConsoleScreen(
                model = model, tele = tele, history = history.value, haptics = haptics,
                sound = sound, onSound = { sound = it },
                buzz = buzz, onBuzz = { buzz = it },
                awake = awake, onAwake = { awake = it },
                onSetpoint = { key, v -> ble.send("$key$v") },
                onMarkFind = { finds.add(tele.level, tracker.position.value?.latitude,
                                         tracker.position.value?.longitude) },
                onFirmware = { screen = Screen.FIRMWARE },
                onFinds = { screen = Screen.FINDS },
                onMap = { screen = Screen.MAP },
                onSettings = { screen = Screen.LOCK },
                onBack = { ble.disconnect(); screen = Screen.DEVICES },
            )

            Screen.FIRMWARE -> FirmwareScreen(
                ident = ident, ble = ble, scope = scope,
                onBack = { screen = Screen.CONSOLE },
            )

            Screen.FINDS -> FindsScreen(
                log = finds,
                onMap = { screen = Screen.MAP },
                onBack = { screen = Screen.CONSOLE },
            )

            Screen.MAP -> MapScreen(
                log = finds, tracker = tracker,
                modifier = Modifier.weight(1f),
                onBack = { screen = Screen.FINDS },
            )

            Screen.LOCK -> LockScreen(
                onOk = { ble.requestConfig(); screen = Screen.SETTINGS },
                onBack = { screen = Screen.CONSOLE },
            )

            Screen.SETTINGS -> SettingsScreen(
                ble = ble, scope = scope, look = look, lang = lang, tele = tele,
                onBack = { screen = Screen.CONSOLE },
            )
        }
        Spacer(Modifier.height(if (scrollable) 28.dp else 10.dp))
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
    lang: Localization,
    onGrant: () -> Unit, onScan: () -> Unit, onPick: (Found) -> Unit,
) {
    // Язык выбирают на первом же экране: прибор нередко отдают напарнику,
    // которому нужен другой язык, а перезапускать приложение ради этого глупо.
    LanguageRow(lang)

    // Отступ сверху: список приборов начинается заметно ниже шапки, а не
    // впритык к ней — так его видно целиком и он не спорит с заголовком.
    Spacer(Modifier.height(26.dp))

    if (!granted) {
        Section {
            Text(S.needPerm.t, color = InkDim, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Brass)) {
                Text(S.allow.t, color = Ground)
            }
        }
        return
    }

    if (found.isEmpty()) {
        Section {
            Text(
                if (scanning) S.searching.t else S.noneFound.t,
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
                    listOfNotNull(m.subtitle, d.serial?.let { S.serialShort.t(it) },
                                  d.fw?.let { S.fwShort.t(it) })
                        .joinToString(" · "),
                    color = InkFaint, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace
                )
            }
            Text("${d.rssi}", color = InkDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }

    // Кнопку тоже опускаем: она главная на экране, ей нужен воздух сверху.
    Spacer(Modifier.height(30.dp))
    Button(
        onClick = onScan, modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (scanning) Edge else Brass)
    ) { Text(if (scanning) S.stop.t else S.searchDevices.t, color = if (scanning) Ink else Ground) }
}

/* -------------------------------------------------------------- консоль */

@Composable
private fun ConsoleScreen(
    model: Model, tele: Telemetry, history: List<Int>,
    haptics: Haptics,
    sound: Boolean, onSound: (Boolean) -> Unit,
    buzz: Boolean, onBuzz: (Boolean) -> Unit,
    awake: Boolean, onAwake: (Boolean) -> Unit,
    onSetpoint: (String, Int) -> Unit,
    onMarkFind: () -> Find,
    onFirmware: () -> Unit, onFinds: () -> Unit, onMap: () -> Unit,
    onSettings: () -> Unit, onBack: () -> Unit,
) {
    var lastFind by remember { mutableStateOf<Find?>(null) }

    Section {
        Gauge(tele.level, model.levels, Modifier.fillMaxWidth().height(140.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Bottom) {
            Text(
                "${tele.level}", color = if (tele.level >= model.levels - 1) Oxide else Brass,
                fontSize = 58.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(9.dp))
            Label(S.stepOf.t(model.levels), Modifier.padding(bottom = 12.dp))
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = EdgeSoft)
        Spacer(Modifier.height(8.dp))
        Label(S.traceTitle.t)
        Trace(history, model.levels, Modifier.fillMaxWidth().height(78.dp).padding(top = 6.dp))
    }

    model.controls.forEach { c ->
        val value = tele.setpoints[c.key] ?: ((c.max * 0.7f).toInt())
        var local by remember(c.key) { mutableStateOf(value.toFloat()) }
        LaunchedEffect(value) { if (local.toInt() != value) local = value.toFloat() }
        Section {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Label(controlName(c), Modifier.weight(1f))
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

    TelemetrySection(tele, model)

    Section {
        Toggle(S.soundPhone.t, sound, onChange = onSound)
        // Проверочный отклик при включении: сразу понятно, что мотор отвечает.
        Toggle(
            if (haptics.available) S.buzzTarget.t else S.buzzNoMotor.t,
            buzz && haptics.available,
            enabled = haptics.available,
        ) { on -> onBuzz(on); if (on) haptics.test() }
        Toggle(S.keepAwake.t, awake, onChange = onAwake)
    }

    Spacer(Modifier.height(13.dp))
    Button(
        onClick = { lastFind = onMarkFind() }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Brass)
    ) { Text(S.markFind.t, color = Ground) }
    lastFind?.let {
        Text(
            S.recorded.t(it.stamp(), it.place()), color = InkFaint,
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 6.dp)
        )
    }

    /*
     * Кнопок стало пять — в одну строку они уже не помещаются и текст в них
     * ужимается до нечитаемого. Раскладываем в два ряда, каждый во всю ширину.
     */
    Spacer(Modifier.height(9.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(S.toDevices.t, color = InkDim, fontSize = 13.sp) }
        OutlinedButton(onClick = onFinds, modifier = Modifier.weight(1f)) { Text(S.navFinds.t, color = InkDim, fontSize = 13.sp) }
        OutlinedButton(onClick = onMap, modifier = Modifier.weight(1f)) { Text(S.navMap.t, color = InkDim, fontSize = 13.sp) }
    }
    Spacer(Modifier.height(9.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        OutlinedButton(onClick = onFirmware, modifier = Modifier.weight(1f)) { Text(S.navFirmware.t, color = Brass, fontSize = 13.sp) }
        OutlinedButton(onClick = onSettings, modifier = Modifier.weight(1f)) { Text(S.navSettings.t, color = Brass, fontSize = 13.sp) }
    }

    // Запас снизу: на телефонах с экранными клавишами последний ряд иначе
    // оказывается прямо над ними и в него трудно попасть.
    Spacer(Modifier.height(18.dp))
}

/* ------------------------------------------------------------ телеметрия */

/** Всё, что прибор сообщает о себе, одним блоком. */
@Composable
private fun TelemetrySection(tele: Telemetry, model: Model) = Section {
    Label(S.teleTitle.t)
    Spacer(Modifier.height(8.dp))

    Row(Modifier.fillMaxWidth()) {
        Stat(S.battery.t, "%.2f V".format(tele.volts), "${tele.batteryPct} %",
            if (tele.batteryPct <= 10) Crit else Slate, Modifier.weight(1f))
        Stat(S.temperature.t, "${tele.tempC} °C",
            if (tele.tempC <= -90) S.sensorSilent.t else S.board.t, InkDim, Modifier.weight(1f))
        Stat(S.heating.t, "${tele.heaterDuty * 100 / 255} %",
            if (tele.heaterDuty > 0) S.pidHeats.t else S.pidIdle.t,
            if (tele.heaterDuty > 0) Oxide else InkDim, Modifier.weight(1f))
    }

    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = EdgeSoft)
    Spacer(Modifier.height(10.dp))

    Row(Modifier.fillMaxWidth()) {
        Stat(S.power.t, if (tele.charging) S.charging.t else S.onBattery.t,
            if (tele.charging) S.usbIn.t else S.usbOut.t,
            if (tele.charging) Ok else InkDim, Modifier.weight(1f))
        Stat(S.step.t, "${tele.level}", S.ofN.t(model.levels), Brass, Modifier.weight(1f))
        Stat(S.setpointsLbl.t,
            model.controls.joinToString(" ") { "${it.key}${tele.setpoints[it.key] ?: 0}" },
            S.asInDevice.t, InkDim, Modifier.weight(1f))
    }
}

/**
 * Название уставки на языке интерфейса. Для незнакомой модели остаётся то,
 * что записано в её описании, — лучше показать хоть что-то, чем пустое место.
 */
@Composable
private fun controlName(c: Control): String = when (c.key) {
    "S" -> S.ctlSensitivity.t
    "V" -> S.ctlVolume.t
    "G" -> S.ctlGround.t
    else -> c.name
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
private fun Toggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, color = if (enabled) Ink else InkFaint,
            fontSize = 14.sp, modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Brass, checkedTrackColor = Edge)
        )
    }

/* --------------------------------------------------------------- графика */

/** Точек на графике и период выборки. */
private const val TRACE_POINTS = 160
private const val TRACE_STEP_MS = 50L

@Composable
private fun Gauge(level: Int, levels: Int, modifier: Modifier) = Canvas(modifier) {
    val cx = size.width / 2f
    val cy = size.height * 0.94f
    val r = minOf(size.width * 0.46f, size.height * 0.88f)
    val thick = r * 0.20f
    val gapDeg = 1.8f
    val n = levels.coerceAtLeast(1)          // деление на ноль дало бы NaN в drawArc
    val spanDeg = (180f - gapDeg * (n - 1)) / n

    for (i in 0 until n) {
        val start = 180f + i * (spanDeg + gapDeg)
        drawArc(
            color = when {
                i >= level -> EdgeSoft
                i >= n - 2 -> Oxide
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
    // Копия на время отрисовки: снаружи список уже неизменяемый, но так кадр
    // гарантированно рисуется по одному и тому же состоянию.
    val pts = history.toList()
    if (pts.size < 2) return@Canvas
    val n = levels.coerceAtLeast(1)
    val pad = 3f
    fun y(v: Int) = size.height - pad - (v.toFloat() / n) * (size.height - pad * 2)
    fun x(i: Int) = i.toFloat() / (pts.size - 1) * size.width

    listOf(2, 4, 6).forEach { drawLine(EdgeSoft, Offset(0f, y(it)), Offset(size.width, y(it)), 1f) }

    val fill = Path().apply {
        moveTo(0f, size.height)
        pts.forEachIndexed { i, v -> lineTo(x(i), y(v)) }
        lineTo(size.width, size.height); close()
    }
    drawPath(fill, Brass.copy(alpha = 0.22f))

    val line = Path().apply {
        pts.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
    }
    drawPath(line, Brass, style = Stroke(width = 2f))

    val last = pts.last()
    drawCircle(if (last >= n - 1) Oxide else Brass, 3.5f, Offset(size.width - 2f, y(last)))
}

/* -------------------------------------------------------------- прошивка */

@Composable
private fun FirmwareScreen(
    ident: Identity?, ble: DetectorBle,
    scope: kotlinx.coroutines.CoroutineScope, onBack: () -> Unit,
) {
    val fw = remember { Firmware(REPO) }
    var latest by remember { mutableStateOf<FirmwareBuild?>(null) }
    var note by remember { mutableStateOf(S.fwPressCheck.t) }
    var progress by remember { mutableStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    val logs = remember { mutableStateListOf<String>() }

    Section {
        InfoRow(S.fwModel.t, Models.of(ident?.model).title)
        InfoRow(S.fwSerial.t, ident?.serial ?: "—")
        InfoRow(S.fwInDevice.t, ident?.fw ?: "—")
        // Своя версия рядом — чтобы сразу видеть, что на телефоне стоит именно
        // та сборка, которую собирали, а не предыдущая.
        InfoRow(S.fwInApp.t, BuildConfig.VERSION_NAME)
        InfoRow(S.fwAvailable.t, note)

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
                        note = S.fwChecking.t
                        val b = runCatching { fw.latestFor(ident!!.model) }.getOrNull()
                        latest = b
                        note = when {
                            b == null -> S.fwNoBuild.t
                            Firmware.compare(b.version, ident!!.fw) > 0 -> S.fwNewer.t(b.version)
                            else -> S.fwSame.t(b.version)
                        }
                        b?.let { logs.add(0, S.fwFound.t(it.model, it.version, it.size / 1024)) }
                    }
                }
            ) { Text(S.fwCheck.t, color = InkDim) }

            Button(
                enabled = !busy && latest != null && ident != null &&
                        Firmware.compare(latest!!.version, ident!!.fw) > 0,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Brass),
                onClick = {
                    scope.launch {
                        busy = true; progress = 0f
                        logs.add(0, S.fwDownloading.t)
                        val image = runCatching { fw.download(latest!!) }.getOrNull()
                        if (image == null) { logs.add(0, S.fwDownloadFail.t); busy = false; return@launch }
                        logs.add(0, S.fwReceived.t(image.size))
                        val err = ble.sendFirmware(image) { progress = it }
                        val installed = latest!!.version

                        /*
                         * Отсутствие подтверждения ещё не значит неудачу.
                         * Прибор мог записать образ и перезагрузиться раньше,
                         * чем уведомление ушло в эфир. Поэтому судим не по
                         * ответу, а по факту: подключаемся заново и смотрим,
                         * какую версию прибор называет теперь.
                         */
                        ble.otaNote.value?.let { logs.add(0, S.fwDeviceSays.t(it)) }
                        logs.add(0, err?.let { S.fwTransfer.t(explainOta(it)) } ?: S.fwWritten.t)
                        logs.add(0, S.fwWaitReboot.t)

                        val back = ble.reconnectAfterUpdate()
                        val now = ble.identity.value?.fw

                        logs.add(0, when {
                            now == installed -> S.fwDone.t(installed)
                            !back            -> S.fwNoAnswer.t
                            now == null      -> S.fwNoPassport.t
                            else             -> S.fwMismatch.t(now, installed)
                        })
                        busy = false
                    }
                }
            ) { Text(S.fwUpdate.t, color = Ground) }
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
    Text(S.fwPowerWarn.t, color = InkDim, fontSize = 12.sp)
    Spacer(Modifier.height(13.dp))
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(S.backToDevice.t, color = InkDim)
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

/* ------------------------------------------------------------------- язык */

/**
 * Ряд языков. На экране приборов — с подписью, в настройках — без неё.
 * Названия языков НЕ переводятся: человек ищет своё слово, а «Немецкий» ему
 * ничего не скажет, если он читает только по-немецки.
 */
@Composable
private fun LanguageRow(lang: Localization, compact: Boolean = false) {
    var current by remember { mutableStateOf(CurrentLang) }
    val scroll = rememberScrollState()

    if (!compact) {
        Spacer(Modifier.height(12.dp))
        Label(S.language.t)
        Spacer(Modifier.height(6.dp))
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Lang.entries.forEach { l ->
            val on = current == l
            Text(
                l.title,
                color = if (on) Ground else InkDim,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(if (on) Brass else Panel, RoundedCornerShape(3.dp))
                    .border(1.dp, if (on) Brass else EdgeSoft, RoundedCornerShape(3.dp))
                    .clickable { current = l; lang.use(l) }
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            )
        }
    }
}

/* ------------------------------------------------------ доступ и настройки */

/** Пароль на вход в настройки прибора. */
private const val SETTINGS_PIN = "1986"

@Composable
private fun LockScreen(onOk: () -> Unit, onBack: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Spacer(Modifier.height(30.dp))
    Section {
        Text(S.lockIntro.t, color = InkDim, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8); wrong = false },
            label = { Text(S.password.t, color = InkFaint) },
            singleLine = true,
            isError = wrong,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Ink, unfocusedTextColor = Ink,
                focusedBorderColor = Brass, unfocusedBorderColor = Edge,
                cursorColor = Brass,
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (wrong) {
            Text(S.wrongPin.t, color = Oxide, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    Spacer(Modifier.height(18.dp))
    Button(
        onClick = { if (pin == SETTINGS_PIN) onOk() else { wrong = true; pin = "" } },
        enabled = pin.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().height(50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Brass, disabledContainerColor = Edge)
    ) { Text(S.enter.t, color = if (pin.isNotEmpty()) Ground else InkFaint) }

    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text(S.back.t, color = InkDim)
    }
    Spacer(Modifier.height(18.dp))
}

/* ---------------------------------------------------------------- настройки */

@Composable
private fun SettingsScreen(
    ble: DetectorBle,
    scope: kotlinx.coroutines.CoroutineScope,
    look: Appearance,
    lang: Localization,
    tele: Telemetry,
    onBack: () -> Unit,
) {
    val cfg by ble.config.collectAsStateWithLifecycle()

    // Поля правим локально, в прибор уходят только по кнопке «Записать».
    var divider by remember { mutableStateOf("") }
    var kp by remember { mutableStateOf("") }
    var ki by remember { mutableStateOf("") }
    var kd by remember { mutableStateOf("") }
    var tsp by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var themeId by remember { mutableStateOf(look.currentId) }

    // Пришли настройки от прибора — заполняем поля один раз.
    LaunchedEffect(cfg) {
        cfg?.let {
            divider = it.divider.toString(); kp = it.kp.toString()
            ki = it.ki.toString(); kd = it.kd.toString(); tsp = it.tempSet.toString()
        }
    }

    // Если прибор молчит дольше трёх секунд — на нём прошивка без этих команд.
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(3000); waited = true }

    Spacer(Modifier.height(20.dp))

    Section {
        Label(S.deviceSection.t)
        Spacer(Modifier.height(4.dp))
        Text(S.dividerHint.t, color = InkFaint, fontSize = 11.5.sp)
        Spacer(Modifier.height(12.dp))

        NumField(S.fieldDivider.t, divider) { divider = it }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { NumField("Kp x100", kp) { kp = it } }
            Box(Modifier.weight(1f)) { NumField("Ki x100", ki) { ki = it } }
            Box(Modifier.weight(1f)) { NumField("Kd x100", kd) { kd = it } }
        }
        Spacer(Modifier.height(8.dp))
        NumField(S.fieldTemp.t, tsp) { tsp = it }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            OutlinedButton(
                onClick = { note = null; ble.requestConfig() },
                modifier = Modifier.weight(1f)
            ) { Text(S.readBtn.t, color = InkDim, fontSize = 13.sp) }
            Button(
                onClick = {
                    scope.launch {
                        ble.sendConfig(
                            DeviceConfig(
                                divider = divider.toIntOrNull() ?: 1000,
                                kp = kp.toIntOrNull() ?: 0,
                                ki = ki.toIntOrNull() ?: 0,
                                kd = kd.toIntOrNull() ?: 0,
                                tempSet = tsp.toIntOrNull() ?: 20,
                            )
                        )
                        note = S.cfgSent.t
                    }
                },
                enabled = cfg != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Brass, disabledContainerColor = Edge)
            ) { Text(S.writeBtn.t, color = if (cfg != null) Ground else InkFaint, fontSize = 13.sp) }
        }

        if (cfg == null && waited) {
            Text(S.noCfg.t, color = Oxide, fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp))
        }
        note?.let {
            Text(it, color = Ok, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }

    Section {
        Label(S.nowShows.t)
        Spacer(Modifier.height(8.dp))
        InfoRow(S.voltage.t, "%.2f V".format(tele.volts))
        InfoRow(S.chargeLbl.t, "${tele.batteryPct} %")
        InfoRow(S.temperature.t, if (tele.tempC <= -90) S.sensorSilent.t else "${tele.tempC} °C")
        InfoRow(S.heating.t, "${tele.heaterDuty * 100 / 255} %")
    }

    Section {
        Label(S.language.t)
        Spacer(Modifier.height(6.dp))
        LanguageRow(lang, compact = true)
    }

    Section {
        Label(S.appearance.t)
        Spacer(Modifier.height(10.dp))
        Themes.all.forEach { t ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { themeId = t.id; look.applyTheme(t.id) }
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = themeId == t.id,
                    onClick = { themeId = t.id; look.applyTheme(t.id) },
                    colors = RadioButtonDefaults.colors(selectedColor = Brass, unselectedColor = InkFaint)
                )
                Spacer(Modifier.width(6.dp))
                Text(t.name, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Row {
                    listOf(t.ground, t.panel, t.accent, t.ink).forEach { c ->
                        Box(
                            Modifier.size(18.dp)
                                .background(Color(c), RoundedCornerShape(2.dp))
                                .border(1.dp, EdgeSoft, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(S.backToDevice.t, color = InkDim)
    }
    Spacer(Modifier.height(18.dp))
}

/** Поле для целого числа: другой ввод физически не пропускаем. */
@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) =
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '-' }.take(7)) },
        label = { Text(label, color = InkFaint, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink, unfocusedTextColor = Ink,
            focusedBorderColor = Brass, unfocusedBorderColor = Edge,
            cursorColor = Brass,
        ),
        modifier = Modifier.fillMaxWidth()
    )

@Composable
private fun FindsScreen(log: FindLog, onMap: () -> Unit, onBack: () -> Unit) {
    // Список живой: автоматика ставит флажки прямо во время проводки.
    val items by log.items.collectAsStateWithLifecycle()

    if (items.isEmpty()) {
        Section {
            Text(S.findsEmpty.t(AUTO_FLAG_LEVEL), color = InkDim, fontSize = 13.sp)
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
            ) {
                Text("${f.level}", color = if (f.level >= 7) Oxide else Brass,
                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.stamp(), color = Ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Text(f.place(), color = InkFaint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (f.auto) Text(S.autoTag.t, color = InkFaint, fontSize = 10.sp)
        }
    }

    Spacer(Modifier.height(13.dp))
    Button(
        onClick = onMap, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Brass),
    ) { Text(S.mapWithFlags.t, color = Ground, fontWeight = FontWeight.SemiBold) }

    Spacer(Modifier.height(9.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text(S.back.t, color = InkDim) }
        OutlinedButton(
            onClick = { log.clear() }, enabled = items.isNotEmpty(), modifier = Modifier.weight(1f)
        ) { Text(S.clearFlags.t, color = if (items.isEmpty()) InkFaint else Crit) }
    }
    // Запас под экранные клавиши телефона.
    Spacer(Modifier.height(18.dp))
}

/**
 * Перевод ответов о ходе обновления на человеческий.
 *
 * С переходом на библиотеку BLEOTA прибор отвечает не текстом, а числовыми
 * кодами, и разбирает их сам модуль связи — сюда причина приходит уже
 * по-русски. Функция осталась одной строкой, чтобы место для перевода было
 * на виду, если протокол снова обрастёт кодами.
 */
private fun explainOta(raw: String): String = raw.trim()

/* ------------------------------------------------------------ разрешения */

private fun scanPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            // Геолокация нужна не ради Bluetooth, а сама по себе: флажки на
            // карте и координаты находок.
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    else
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** Хватает ли прав, чтобы искать приборы. Карта переживёт отказ отдельно. */
private fun hasScanPermission(ctx: android.content.Context): Boolean {
    val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    return needed.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
}
