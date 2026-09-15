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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
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

private enum class Screen { DEVICES, CONSOLE, FIRMWARE, FINDS, MAP, SETTINGS }

class MainActivity : ComponentActivity() {

    private lateinit var ble: DetectorBle
    private lateinit var audio: VcoAudio
    private lateinit var haptics: Haptics
    private lateinit var finds: FindLog
    private lateinit var tracker: LocationTracker
    private lateinit var flagger: AutoFlagger
    private lateinit var look: Appearance
    private lateinit var lang: Localization
    private lateinit var prefs: Prefs
    private lateinit var gps: GpsGate
    private lateinit var bright: BrightnessControl
    private lateinit var sweep: SweepBuffer
    private lateinit var rec: SweepRecorder

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
        prefs = Prefs(this)
        gps = GpsGate(tracker)
        bright = BrightnessControl(this)
        sweep = SweepBuffer()
        rec = SweepRecorder(this)
        sweep.dip = prefs.sepDip
        sweep.windowMs = SweepBuffer.windowFor(prefs.sweepSpeed)

        // Порог «найден металл» один на всё: флажок, GPS и подсветка ступени.
        flagger.threshold = prefs.flagLevel - 1
        gps.armLevel = prefs.flagLevel

        // Звук берёт настройки сразу, не дожидаясь, пока его включат.
        audio.mode = prefs.soundMode
        audio.bgVolume = prefs.bgVolume
        audio.sigVolume = prefs.sigVolume

        bright.onValue = { v -> if (prefs.autoBrightness) setWindowBrightness(v) }

        /*
         * Ступень цели идёт в звук и вибрацию коротким путём — прямо из
         * потока Bluetooth, минуя StateFlow и перерисовку экрана. Шкала,
         * график, флажки и GPS остаются на обычном пути: им спешить некуда,
         * а трогать файлы и приёмник из чужого потока нельзя.
         */
        ble.onLevel = { lv ->
            audio.level = lv.toFloat()
            haptics.onLevel(lv)
        }

        /*
         * Отсчёты для графика — тоже из потока Bluetooth. Буфер держит
         * состояние Compose, но читают его внутри блока отрисовки, поэтому
         * приход строки перерисовывает холст, а не пересобирает экран.
         */
        ble.onSample = { lv, deviceMs -> sweep.add(lv, deviceMs) }

        /*
         * Запись прохода берёт строку до разбора — ей нужно то, что реально
         * пришло из эфира, а не то, что приложение сумело из этого понять.
         */
        ble.onRawLine = { line -> rec.feed(line) }

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
                    prefs = prefs, gps = gps, bright = bright, sweep = sweep, rec = rec,
                    onBrightness = { v -> setWindowBrightness(v) },
                    onBackground = { on ->
                        if (on) DetectorService.start(this) else DetectorService.stop(this)
                    },
                    onKeepAwake = { on ->
                        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                )
                }
            }
        }
    }

    /*
     * Яркость правим у окна, а не в системных настройках: так изменение
     * действует только пока приложение на экране и не остаётся у человека
     * после выхода. Значение 0.02 вместо нуля — на нуле часть прошивок гасит
     * подсветку совсем, и экран не оживает даже от касания.
     */
    private fun setWindowBrightness(v: Float) {
        window.attributes = window.attributes.apply {
            screenBrightness = v.coerceIn(0.02f, 1f)
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.autoBrightness) bright.start() else setWindowBrightness(prefs.brightness)
    }

    override fun onPause() {
        super.onPause()
        bright.stop()
        /*
         * GPS и звук здесь НЕ выключаем. Смысл фонового режима именно в том,
         * чтобы прибор продолжал вести проводку с погашенным экраном; за
         * жизнь процесса отвечает служба переднего плана.
         */
    }

    override fun onDestroy() {
        super.onDestroy()
        audio.enabled = false
        bright.stop()
        gps.stop()
        tracker.stop()
        ble.disconnect()
        DetectorService.stop(this)
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
    prefs: Prefs,
    gps: GpsGate,
    bright: BrightnessControl,
    sweep: SweepBuffer,
    rec: SweepRecorder,
    onBrightness: (Float) -> Unit,
    onBackground: (Boolean) -> Unit,
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

    // Экран погашен программно: приложение работает, поверх всего чёрный слой.
    var blanked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sound) { audio.enabled = sound }

    // Настройки звука уходят в генератор сразу, без перезапуска дорожки.
    LaunchedEffect(prefs.soundMode, prefs.bgVolume, prefs.sigVolume) {
        audio.mode = prefs.soundMode
        audio.bgVolume = prefs.bgVolume
        audio.sigVolume = prefs.sigVolume
    }

    // Порог находки правит сразу три вещи, поэтому держим их вместе.
    LaunchedEffect(prefs.flagLevel) {
        flagger.threshold = prefs.flagLevel - 1
        gps.armLevel = prefs.flagLevel
    }

    // Яркость: автоматика слушает датчик, ручная ставится сразу.
    LaunchedEffect(prefs.autoBrightness, prefs.brightness, blanked) {
        when {
            blanked -> onBrightness(0.02f)
            prefs.autoBrightness -> bright.start()
            else -> { bright.stop(); onBrightness(prefs.brightness) }
        }
    }

    LaunchedEffect(prefs.background) { onBackground(prefs.background) }

    // Раз в секунду гасим приёмник, если время удержания вышло.
    LaunchedEffect(Unit) {
        while (isActive) { delay(1000); gps.tick() }
    }
    LaunchedEffect(buzz) { haptics.enabled = buzz }
    LaunchedEffect(awake) { onKeepAwake(awake) }
    var granted by remember { mutableStateOf(hasScanPermission(ctx)) }

    val link by ble.link.collectAsStateWithLifecycle()
    val found by ble.found.collectAsStateWithLifecycle()
    val ident by ble.identity.collectAsStateWithLifecycle()
    val tele by ble.telemetry.collectAsStateWithLifecycle()

    val model = Models.of(ident?.model)

    /*
     * Отсчёты проводки копятся в SweepBuffer по приходу строк — см. Sweep.kt.
     * Здесь их только подхватывают рисовальщики; пополняет буфер поток
     * Bluetooth, минуя рекомпозицию (ble.onSample в onCreate).
     *
     * Прежде тут стояла выборка по таймеру раз в 50 мс, и два предмета в
     * десятке сантиметров сливались в один горб: провал между ними приходился
     * между отсчётами.
     */
    LaunchedEffect(prefs.sepDip) { sweep.dip = prefs.sepDip }
    LaunchedEffect(prefs.sweepSpeed) { sweep.windowMs = SweepBuffer.windowFor(prefs.sweepSpeed) }
    LaunchedEffect(link) { if (link != Link.READY) sweep.clear() }

    /*
     * Здесь остались только те потребители ступени, которым нужен главный
     * поток: приёмник (LocationManager требует Looper) и журнал находок
     * (пишет файл). Звук и вибрация сюда больше не заходят — они получают
     * ступень напрямую из потока Bluetooth, см. ble.onLevel в onCreate.
     */
    LaunchedEffect(tele.level) {
        /*
         * Приёмник поднимаем ПЕРЕД тем, как ставить флажок: у GpsGate уже
         * может быть готовая позиция с прошлой находки, и тогда точка ляжет
         * с координатами сразу.
         */
        gps.onLevel(tele.level)
        flagger.onLevel(tele.level)
    }

    // Число делений зависит от модели, а не от ступени: ставим при смене модели.
    LaunchedEffect(model.levels) { audio.levels = model.levels }
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
                model = model, tele = tele, sweep = sweep, haptics = haptics,
                dots = prefs.plotDots, onDots = { prefs.plotDots = it },
                rec = rec,
                onRecord = {
                    if (rec.active) rec.stop()
                    else rec.start(
                        model = ident?.model ?: model.id,
                        fw = ident?.fw ?: "-",
                        serial = ident?.serial ?: "-",
                        dip = prefs.sepDip,
                        sweepSpeed = prefs.sweepSpeed,
                    )
                },
                onBlank = { blanked = true },
                sound = sound, onSound = { sound = it },
                buzz = buzz, onBuzz = { buzz = it },
                awake = awake, onAwake = { awake = it },
                onSetpoint = { key, v -> ble.send("$key$v") },
                onMarkFind = { finds.add(tele.level, tracker.position.value?.latitude,
                                         tracker.position.value?.longitude) },
                onFirmware = { screen = Screen.FIRMWARE },
                onFinds = { screen = Screen.FINDS },
                onMap = { screen = Screen.MAP },
                onSettings = { ble.requestConfig(); screen = Screen.SETTINGS },
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
                log = finds, tracker = tracker, gps = gps,
                modifier = Modifier.weight(1f),
                onBack = { screen = Screen.FINDS },
            )

            Screen.SETTINGS -> SettingsScreen(
                ble = ble, scope = scope, look = look, lang = lang,
                prefs = prefs, bright = bright, tele = tele,
                onBack = { screen = Screen.CONSOLE },
            )
        }
        Spacer(Modifier.height(if (scrollable) 28.dp else 10.dp))
    }

    /*
     * Погашенный экран.
     *
     * Совсем выключить подсветку из обычного приложения нельзя — на это нужны
     * права администратора устройства. Поэтому делаем то же по сути: яркость
     * окна в минимум и чёрное полотно поверх интерфейса. Звук, вибрация,
     * Bluetooth и запись находок при этом продолжают работать.
     */
    if (blanked) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable { blanked = false },
            contentAlignment = Alignment.Center
        ) {
            Text(
                S.tapToWake.t, color = Color(0xFF202020), fontSize = 12.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(28.dp)
            )
        }
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
    model: Model, tele: Telemetry, sweep: SweepBuffer,
    dots: Boolean, onDots: (Boolean) -> Unit,
    rec: SweepRecorder, onRecord: () -> Unit,
    haptics: Haptics,
    sound: Boolean, onSound: (Boolean) -> Unit,
    buzz: Boolean, onBuzz: (Boolean) -> Unit,
    awake: Boolean, onAwake: (Boolean) -> Unit,
    onSetpoint: (String, Int) -> Unit,
    onMarkFind: () -> Find,
    onFirmware: () -> Unit, onFinds: () -> Unit, onMap: () -> Unit,
    onSettings: () -> Unit, onBlank: () -> Unit, onBack: () -> Unit,
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

        /*
         * Переключатель вида проводки.
         *
         * Линия и точки показывают одни и те же отсчёты, разница в честности.
         * Линия между двумя соседними отсчётами дорисовывает плавный переход,
         * которого прибор не присылал. Точки не дорисовывают ничего: видно
         * ровно то, что пришло, и по просветам между ними — где связь молчала,
         * а где сигнал в самом деле проваливался. Для поиска двух предметов
         * рядом это и важно.
         */
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(S.traceTitle.t, Modifier.weight(1f))
            Pick(S.viewTrace.t, !dots, Modifier.width(74.dp)) { onDots(false) }
            Spacer(Modifier.width(6.dp))
            Pick(S.viewDots.t, dots, Modifier.width(74.dp)) { onDots(true) }
        }

        if (dots) {
            DotField(sweep, model.levels,
                     Modifier.fillMaxWidth().height(190.dp).padding(top = 8.dp))

            /*
             * Счётчик: подпись стоит всегда, меняется только цифра.
             *
             * Раньше вся строка появлялась при двух целях и исчезала при
             * одной — экран из-за этого дёргался, всё под ней прыгало.
             * Теперь место занято постоянно.
             *
             * Цифра моноширинная нарочно: у пропорционального шрифта единица
             * уже двойки, и число ездило бы по строке на каждой смене.
             *
             * sweep.count — отдельное состояние от peaks, и читается оно
             * здесь, в рекомпозиции. Меняется редко (только когда цель
             * появилась или ушла из окна), поэтому экран не пересобирается
             * на каждом пришедшем отсчёте — в отличие от самого поля, которое
             * читает буфер внутри блока отрисовки.
             */
            val found = sweep.count
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(S.targetsLbl.t, Modifier.weight(1f))
                Text(
                    "$found",
                    color = if (found >= 2) Oxide else InkDim,
                    fontSize = 30.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        } else {
            Trace(sweep, model.levels,
                  Modifier.fillMaxWidth().height(78.dp).padding(top = 6.dp))
        }
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

    /* ------------------------------------------------- запись прохода ----- */
    /*
     * Кнопка живёт на экране прибора, а не в настройках, нарочно: её жмут
     * в поле, за секунду до проводки и сразу после неё.
     *
     * Запись идёт в память и ложится на диск одним куском при остановке, иначе
     * дисковый ввод-вывод вклинивался бы в приём строк.
     */
    Section {
        val ctx = LocalContext.current
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onRecord,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (rec.active) Crit else Panel,
                    contentColor = if (rec.active) Ground else Brass,
                ),
            ) { Text(if (rec.active) S.recStop.t else S.recStart.t, fontSize = 13.sp) }

            if (rec.active) {
                Spacer(Modifier.width(10.dp))
                Text(
                    S.recLines.t(rec.lines), color = InkDim, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        rec.saved?.let { f ->
            Spacer(Modifier.height(8.dp))
            Text(S.recSaved.t(f.name), color = InkFaint, fontSize = 11.sp,
                 fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { Share.file(ctx, f, S.recCaption.t) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(S.recShare.t, color = Brass, fontSize = 13.sp) }
        }
    }

    Spacer(Modifier.height(13.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Button(
            onClick = { lastFind = onMarkFind() }, modifier = Modifier.weight(1f).height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Brass)
        ) { Text(S.markFind.t, color = Ground) }

        /*
         * Гашение экрана — рядом с главной кнопкой, а не в настройках: его
         * жмут в поле, не глядя, когда прибор уже ведёт проводку.
         */
        Button(
            onClick = onBlank, modifier = Modifier.weight(1f).height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Ink)
        ) { Text(S.screenOff.t, fontSize = 13.sp) }
    }
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
        /*
         * НА ЗАРЯДКЕ НАПРЯЖЕНИЕ И ПРОЦЕНТ НЕ ПОКАЗЫВАЕМ.
         *
         * Пока идёт заряд, на АКБ висит напряжение зарядника — оно выше
         * собственного напряжения батареи, и процент, посчитанный по нему,
         * завышен. Показывать заведомо неверное число хуже, чем не показывать
         * ничего: по нему принимают решение, хватит ли заряда на выход.
         */
        Stat(S.battery.t,
            if (tele.charging) S.charging.t else "%.2f V".format(tele.volts),
            if (tele.charging) S.whileCharging.t else "${tele.batteryPct} %",
            when {
                tele.charging -> Ok
                tele.batteryPct <= 10 -> Crit
                else -> Slate
            },
            Modifier.weight(1f))
        Stat(S.temperature.t, "${tele.tempC} °C",
            if (tele.tempC <= -90) S.sensorSilent.t else S.board.t, InkDim, Modifier.weight(1f))
        Stat(S.heating.t, "${tele.heaterDuty * 100 / 255} %",
            if (tele.heaterDuty > 0) S.pidHeats.t else S.pidIdle.t,
            if (tele.heaterDuty > 0) Oxide else InkDim, Modifier.weight(1f))
    }

    /*
     * Линейка компараторов как она есть.
     *
     * Прибор шлёт не только число горящих делений, но и маску — какие именно
     * сработали.
     *
     * ПОРЯДОК КЛЕТОК ОБРАТНЫЙ НОМЕРАМ БИТОВ, И ЭТО НАРОЧНО.
     *
     * Бит 0 маски — это вход MCP 4, который в таблице SEGMENTS стоит первым.
     * Но на экране прибора он нарисован СПРАВА: линейка заполняется слева
     * направо, и первым загорается вход 11, то есть старший бит. Рисуй я
     * клетки по возрастанию битов, ряд заполнялся бы справа налево — зеркально
     * прибору, и сравнивать две картинки стало бы невозможно. Поэтому слева
     * рисуется старший бит, и ряд повторяет шкалу прибора один в один.
     *
     * Дырка в середине означает, что шкала не сплошная, — тогда число горящих
     * делений и номер старшего это разные вещи.
     *
     * Старая прошивка маску не шлёт, и тогда ряда просто нет.
     */
    tele.mask?.let { m ->
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = EdgeSoft)
        Spacer(Modifier.height(10.dp))
        Label(S.comparators.t)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in model.levels - 1 downTo 0) {
                val on = (m shr i) and 1 == 1
                Box(
                    Modifier.weight(1f).height(16.dp)
                        .background(if (on) Brass else Panel, RoundedCornerShape(2.dp))
                        .border(1.dp, if (on) Brass else Edge, RoundedCornerShape(2.dp))
                )
            }
        }
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

/*
 * Оба вида проводки рисуют ОДНИ И ТЕ ЖЕ отсчёты из SweepBuffer, и оба читают
 * буфер внутри блока Canvas. Это не мелочь: чтение состояния в блоке отрисовки
 * приводит к перерисовке холста, а чтение в теле composable-функции — к
 * пересборке всего экрана. При тридцати-шестидесяти строках в секунду второе
 * съело бы телефон.
 *
 * Горизонталь у обоих — ВРЕМЯ, а не номер отсчёта. Строки приходят неравномерно
 * (прибор шлёт их по изменению ступени), и раскладывать их по равным шагам
 * значило бы врать о том, когда что случилось: два всплеска в сотне миллисекунд
 * друг от друга разъехались бы по всему экрану, а долгая тишина сжалась бы в точку.
 */

/**
 * Общая разметка: пересчёт «ступень и время» в координаты холста.
 *
 * Длина окна приходит из буфера, а не константой: её крутит настройка
 * «Размах», и обе картинки обязаны меняться вместе со счётчиком.
 */
private class PlotGrid(
    val w: Float, val h: Float, val levels: Int, val tEnd: Long, val window: Long,
    val padX: Float = 4f, val padY: Float = 5f,
) {
    val n = levels.coerceAtLeast(1)
    private val tStart = tEnd - window
    fun y(level: Float) = h - padY - (level / n) * (h - padY * 2)
    fun x(t: Long) = padX + ((t - tStart).toFloat() / window) * (w - padX * 2)
}

@Composable
private fun Trace(sweep: SweepBuffer, levels: Int, modifier: Modifier) = Canvas(modifier) {
    // Подписка на счётчик кадров: условие никогда не сработает, но чтение
    // состояния настоящее, и перерисовка идёт по кадрам, а не по отсчётам.
    if (sweep.tick < 0) return@Canvas
    val pts = sweep.samples
    if (pts.size < 2) return@Canvas
    val g = PlotGrid(size.width, size.height, levels, pts.last().t, sweep.windowMs)

    listOf(2, 4, 6).forEach {
        drawLine(EdgeSoft, Offset(0f, g.y(it.toFloat())), Offset(size.width, g.y(it.toFloat())), 1f)
    }

    val fill = Path().apply {
        moveTo(g.x(pts.first().t), size.height)
        pts.forEach { lineTo(g.x(it.t), g.y(it.level.toFloat())) }
        lineTo(g.x(pts.last().t), size.height)
        close()
    }
    drawPath(fill, Brass.copy(alpha = 0.22f))

    val line = Path().apply {
        pts.forEachIndexed { i, p ->
            if (i == 0) moveTo(g.x(p.t), g.y(p.level.toFloat()))
            else lineTo(g.x(p.t), g.y(p.level.toFloat()))
        }
    }
    drawPath(line, Brass, style = Stroke(width = 2f))

    val last = pts.last()
    drawCircle(if (last.level >= g.n - 1) Oxide else Brass, 3.5f,
               Offset(g.x(last.t), g.y(last.level.toFloat())))
}

/**
 * Поле целей: каждая принятая строка — одна точка, и ничего между ними.
 *
 * Слева колонка номеров делений — высоту всплеска читают, а не прикидывают.
 * У правого края черта «сейчас», от неё картинка уезжает влево. Свежие точки
 * ярче старых, цвет течёт от латунного к оранжевому по силе сигнала.
 * Найденные цели обведены ореолом, ширина ореола — по силе отклика, и рядом
 * номер цели по порядку в проходе: два кружка с цифрами 1 и 2 и есть ответ
 * «под катушкой было два предмета».
 */
@Composable
private fun DotField(sweep: SweepBuffer, levels: Int, modifier: Modifier) {
    /*
     * Подписи рисуются родным Canvas: в блоке отрисовки Compose текста нет.
     * Кисти держим снаружи и пересоздаём только при смене оформления — иначе
     * на каждый пришедший отсчёт выделялось бы по два объекта.
     */
    val axisPaint = remember(InkFaint) {
        android.graphics.Paint().apply { color = InkFaint.toArgb(); isAntiAlias = true }
    }
    val markPaint = remember(Oxide) {
        android.graphics.Paint().apply {
            color = Oxide.toArgb()
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
    }

    Canvas(modifier) {
        /*
         * Подписка на счётчик кадров, а не на сами отсчёты.
         *
         * Раньше холст читал список отсчётов напрямую, и каждая пришедшая
         * строка — до шестидесяти в секунду — перерисовывала всё поле: две
         * сотни кружков с прозрачностью, ореолы, подписи осей. При длинном
         * размахе это и было тем «немного тормозит». Теперь буфер сам
         * повышает tick не чаще тридцати раз в секунду, а данные холст берёт
         * уже обычными полями.
         */
        if (sweep.tick < 0) return@Canvas
        val pts = sweep.samples
        val peaks = sweep.peaks
        val window = sweep.windowMs
        val gutter = 20.dp.toPx()                 // колонка номеров делений
        val g = PlotGrid(size.width, size.height, levels,
                         pts.lastOrNull()?.t ?: 0L, window,
                         padX = gutter, padY = 12f)
        val right = size.width - 3f

        axisPaint.textSize = 9.sp.toPx()
        markPaint.textSize = 13.sp.toPx()

        // Сетка приглушена нарочно: она разметка, а не данные, и спорить по
        // яркости с точками не должна. Чётные деления чуть заметнее.
        for (i in 0..g.n) {
            val yy = g.y(i.toFloat())
            drawLine(
                EdgeSoft.copy(alpha = if (i % 2 == 0) 0.55f else 0.22f),
                Offset(gutter, yy), Offset(right, yy), 1f
            )
            if (i > 0) drawContext.canvas.nativeCanvas.drawText(
                "$i", 2f, yy + axisPaint.textSize / 3f, axisPaint
            )
        }

        // Черта «сейчас» — от неё картинка уезжает влево.
        drawLine(InkFaint.copy(alpha = 0.45f),
                 Offset(right, g.padY), Offset(right, size.height - g.padY), 1.5f)

        if (pts.isEmpty()) return@Canvas
        val tEnd = pts.last().t

        /*
         * Точки прореживаем по пикселям.
         *
         * При длинном размахе в окне лежит до двух сотен отсчётов, а ширина
         * поля — три-четыре сотни точек экрана. Соседние отсчёты на ровном
         * участке ложатся в один и тот же пиксель, и рисовать их по второму
         * разу значит платить за невидимое. Ключ — упакованные координаты.
         */
        val drawn = HashSet<Int>(pts.size * 2)
        pts.forEach { p ->
            val px = g.x(p.t)
            val py = g.y(p.level.toFloat())
            if (!drawn.add((px.toInt() shl 16) or (py.toInt() and 0xFFFF))) return@forEach
            val age = ((tEnd - p.t).toFloat() / window).coerceIn(0f, 1f)
            val force = (p.level.toFloat() / g.n).coerceIn(0f, 1f)
            drawCircle(
                lerp(Brass, Oxide, force).copy(alpha = 1f - age * 0.75f),
                3.2f, Offset(px, py)
            )
        }

        peaks.forEachIndexed { i, p ->
            val cx = g.x(p.t)
            val cy = g.y(p.level.toFloat())
            val force = (p.level.toFloat() / g.n).coerceIn(0.25f, 1f)
            val r = 7.dp.toPx() * (0.6f + 0.7f * force)

            // Ореол — три кольца с убывающей плотностью. Дешевле настоящего
            // размытия и на тёмном фоне выглядит так же.
            for (k in 3 downTo 1) {
                drawCircle(Oxide.copy(alpha = 0.09f * k), r * (1f + 0.45f * (3 - k)),
                           Offset(cx, cy))
            }
            drawCircle(Oxide, r * 0.5f, Offset(cx, cy))
            drawLine(Oxide.copy(alpha = 0.28f),
                     Offset(cx, cy), Offset(cx, size.height - g.padY), 1f)

            // Номер цели над кружком, а у верхнего края — под ним, иначе срежется.
            val ty = if (cy < size.height * 0.35f) cy + r + markPaint.textSize
                     else cy - r - markPaint.textSize / 2f
            drawContext.canvas.nativeCanvas.drawText("${i + 1}", cx, ty, markPaint)
        }
    }
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
    var open by remember { mutableStateOf(false) }

    if (!compact) {
        Spacer(Modifier.height(12.dp))
        Label(S.language.t)
        Spacer(Modifier.height(6.dp))
    }

    // Список закрыт по умолчанию: семь языков одной строкой не помещаются, а
    // прокручиваемый ряд на первом экране только сбивал с толку.
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .background(Panel, RoundedCornerShape(3.dp))
                .border(1.dp, EdgeSoft, RoundedCornerShape(3.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(current.title, color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("▾", color = Brass, fontSize = 14.sp)
        }

        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.background(Panel)
        ) {
            Lang.entries.forEach { l ->
                DropdownMenuItem(
                    text = {
                        Text(
                            l.title,
                            color = if (l == current) Brass else Ink,
                            fontSize = 14.sp,
                        )
                    },
                    onClick = { current = l; lang.use(l); open = false }
                )
            }
        }
    }
}

/* ---------------------------------------------------------------- настройки */

/** Пароль на запись настроек в прибор. */
private const val SETTINGS_PIN = "1986"

@Composable
private fun SettingsScreen(
    ble: DetectorBle,
    scope: kotlinx.coroutines.CoroutineScope,
    look: Appearance,
    lang: Localization,
    prefs: Prefs,
    bright: BrightnessControl,
    tele: Telemetry,
    onBack: () -> Unit,
) {
    val cfg by ble.config.collectAsStateWithLifecycle()

    var divider by remember { mutableStateOf("") }
    var kp by remember { mutableStateOf("") }
    var ki by remember { mutableStateOf("") }
    var kd by remember { mutableStateOf("") }
    var tsp by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var themeId by remember { mutableStateOf(look.currentId) }

    // Пароль спрашиваем не на входе, а в момент записи: смотреть настройки
    // безопасно, опасно их менять.
    var askPin by remember { mutableStateOf(false) }

    LaunchedEffect(cfg) {
        cfg?.let {
            divider = it.divider.toString(); kp = it.kp.toString()
            ki = it.ki.toString(); kd = it.kd.toString(); tsp = it.tempSet.toString()
        }
    }

    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(3000); waited = true }

    fun writeToDevice() {
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
    }

    Spacer(Modifier.height(20.dp))

    /* ------------------------------------------------------------ звук ---- */
    Section {
        Label(S.soundSection.t)
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pick(S.soundNormal.t, prefs.soundMode == SoundMode.NORMAL, Modifier.weight(1f)) {
                prefs.soundMode = SoundMode.NORMAL
            }
            Pick(S.soundHearing.t, prefs.soundMode == SoundMode.HEARING, Modifier.weight(1f)) {
                prefs.soundMode = SoundMode.HEARING
            }
        }
        if (prefs.soundMode == SoundMode.HEARING) {
            Text(S.soundHearingHint.t, color = InkFaint, fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(12.dp))
        LevelSlider(S.volBg.t, prefs.bgVolume) { prefs.bgVolume = it }
        LevelSlider(S.volSig.t, prefs.sigVolume) { prefs.sigVolume = it }
    }

    /* --------------------------------------------------------- яркость ---- */
    Section {
        Label(S.brightSection.t)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pick(S.brightAuto.t, prefs.autoBrightness, Modifier.weight(1f),
                enabled = bright.available) { prefs.autoBrightness = true }
            Pick(S.brightManual.t, !prefs.autoBrightness, Modifier.weight(1f)) {
                prefs.autoBrightness = false
            }
        }
        if (!bright.available) {
            Text(S.noLightSensor.t, color = Oxide, fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (!prefs.autoBrightness) {
            Spacer(Modifier.height(10.dp))
            LevelSlider(S.brightSection.t, prefs.brightness) { prefs.brightness = it }
        }
    }

    /* ------------------------------------------------ порог находки ------- */
    Section {
        Label(S.flagLevelTitle.t)
        Spacer(Modifier.height(4.dp))
        Text(S.flagLevelHint.t, color = InkFaint, fontSize = 11.5.sp)
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            (1..8).forEach { lv ->
                val on = prefs.flagLevel == lv
                Text(
                    "$lv",
                    color = if (on) Ground else InkDim,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                        .background(if (on) Brass else Panel, RoundedCornerShape(3.dp))
                        .border(1.dp, if (on) Brass else EdgeSoft, RoundedCornerShape(3.dp))
                        .clickable { prefs.flagLevel = lv }
                        .padding(vertical = 10.dp)
                )
            }
        }
        Text(S.gpsHint.t, color = InkFaint, fontSize = 11.5.sp,
            modifier = Modifier.padding(top = 10.dp))
    }

    /* ------------------------------------------------------ размах -------- */
    Section {
        Label(S.sweepSection.t)
        Spacer(Modifier.height(4.dp))
        Text(S.sweepHint.t, color = InkFaint, fontSize = 11.5.sp)
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(S.sweepFast, S.sweepMid, S.sweepSlow).forEachIndexed { i, name ->
                Pick(name.t, prefs.sweepSpeed == i, Modifier.weight(1f)) { prefs.sweepSpeed = i }
            }
        }
    }

    /* --------------------------------------------- разделение целей ------- */
    Section {
        Label(S.sepSection.t)
        Spacer(Modifier.height(4.dp))
        Text(S.sepHint.t, color = InkFaint, fontSize = 11.5.sp)
        Spacer(Modifier.height(10.dp))
        Label(S.sepDipLbl.t)
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..3).forEach { d ->
                Pick("$d", prefs.sepDip == d, Modifier.weight(1f)) { prefs.sepDip = d }
            }
        }
    }

    /* ------------------------------------------------------ фон ----------- */
    Section {
        Toggle(S.backgroundWork.t, prefs.background) { prefs.background = it }
        Text(S.bgRunning.t, color = InkFaint, fontSize = 11.5.sp)
    }

    /* --------------------------------------------------- прибор ----------- */
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
                onClick = { askPin = true },
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
        // На зарядке — по той же причине, что и на экране прибора.
        InfoRow(S.voltage.t, if (tele.charging) S.charging.t else "%.2f V".format(tele.volts))
        InfoRow(S.chargeLbl.t, if (tele.charging) S.whileCharging.t else "${tele.batteryPct} %")
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

    if (askPin) {
        PinDialog(
            onOk = { askPin = false; writeToDevice() },
            onCancel = { askPin = false },
        )
    }
}

/** Ввод пароля перед записью в прибор. */
@Composable
private fun PinDialog(onOk: () -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Panel,
        title = { Text(S.password.t, color = Ink) },
        text = {
            Column {
                Text(S.enterPinToWrite.t, color = InkDim, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8); wrong = false },
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
        },
        confirmButton = {
            Button(
                onClick = { if (pin == SETTINGS_PIN) onOk() else { wrong = true; pin = "" } },
                colors = ButtonDefaults.buttonColors(containerColor = Brass)
            ) { Text(S.enter.t, color = Ground) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(S.cancel.t, color = InkDim) }
        }
    )
}

/** Кнопка-переключатель «выбран / не выбран». */
@Composable
private fun Pick(
    text: String, selected: Boolean, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit,
) = Text(
    text,
    color = when {
        !enabled -> InkFaint
        selected -> Ground
        else -> InkDim
    },
    fontSize = 13.sp,
    textAlign = TextAlign.Center,
    modifier = modifier
        .background(if (selected && enabled) Brass else Panel, RoundedCornerShape(3.dp))
        .border(1.dp, if (selected && enabled) Brass else EdgeSoft, RoundedCornerShape(3.dp))
        .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
        .padding(vertical = 11.dp)
)

/** Ползунок 0..1 с подписью и процентами. */
@Composable
private fun LevelSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    var local by remember(label) { mutableStateOf(value) }
    LaunchedEffect(value) { if (kotlin.math.abs(local - value) > 0.001f) local = value }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Label(label, Modifier.weight(1f))
        Text("${(local * 100).toInt()} %", color = Ink, fontSize = 13.sp,
            fontFamily = FontFamily.Monospace)
    }
    Slider(
        value = local, onValueChange = { local = it },
        onValueChangeFinished = { onChange(local) },
        colors = SliderDefaults.colors(thumbColor = Brass, activeTrackColor = Brass,
            inactiveTrackColor = Edge)
    )
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
    val ctx = LocalContext.current

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

            // Отправить одну точку прямо из списка: чаще всего нужна именно
            // последняя находка, а не весь журнал.
            if (f.lat != null && f.lon != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    S.shareCoords.t, color = Brass, fontSize = 11.sp,
                    modifier = Modifier
                        .border(1.dp, EdgeSoft, RoundedCornerShape(3.dp))
                        .clickable { Share.point(ctx, f.lat, f.lon, S.shareCaption.t) }
                        .padding(horizontal = 9.dp, vertical = 6.dp)
                )
            }
        }
    }

    Spacer(Modifier.height(13.dp))
    Button(
        onClick = onMap, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Brass),
    ) { Text(S.mapWithFlags.t, color = Ground, fontWeight = FontWeight.SemiBold) }

    Spacer(Modifier.height(9.dp))
    OutlinedButton(
        onClick = { Share.points(ctx, items, S.shareCaption.t) },
        enabled = items.any { it.lat != null },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(S.shareAllPoints.t,
            color = if (items.any { it.lat != null }) Brass else InkFaint)
    }

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
