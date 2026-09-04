package com.alijon.detector

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/* ===========================================================================
 *  Языки интерфейса
 *
 *  Надписи держим не в res/values-xx, а прямо здесь, и вот почему: язык в этом
 *  приложении выбирает человек, а не система. Оператор с русским телефоном
 *  вполне может отдать прибор напарнику, которому нужен английский, и наоборот.
 *  Ресурсы Android так не умеют без плясок с пересозданием контекста.
 *
 *  Номер выбранного языка — состояние Compose. Поэтому смена языка перерисовывает
 *  экраны сама, без перезапуска: каждое место, которое читает надпись, на это
 *  состояние подписано.
 * ===========================================================================
 */

/** Порядок здесь задаёт порядок полей в S7 и порядок в списке выбора. */
enum class Lang(val code: String, val title: String, val rtl: Boolean = false) {
    RU("ru", "Русский"),
    EN("en", "English"),
    DE("de", "Deutsch"),
    FR("fr", "Français"),
    ES("es", "Español"),
    ZH("zh", "中文"),
    AR("ar", "العربية", rtl = true),
}

/** Текущий язык. Меняется на ходу — экраны перерисовываются сами. */
internal var CurrentLang by mutableStateOf(Lang.RU)

/** Надпись на семи языках. Порядок полей совпадает с порядком в enum Lang. */
class S7(
    private val ru: String,
    private val en: String,
    private val de: String,
    private val fr: String,
    private val es: String,
    private val zh: String,
    private val ar: String,
) {
    /** Текст на текущем языке. Читается внутри Compose — значит, подписан. */
    val t: String
        get() = when (CurrentLang) {
            Lang.RU -> ru; Lang.EN -> en; Lang.DE -> de; Lang.FR -> fr
            Lang.ES -> es; Lang.ZH -> zh; Lang.AR -> ar
        }

    /*
     * Подстановка значений в надписи с %s и %d.
     *
     * Locale.ROOT нарочно: без него на телефоне с арабской локалью числа
     * выводятся индо-арабскими цифрами (١٢٣), и версия прошивки или число
     * флажков превращаются в загадку для того, кто их сверяет с прибором.
     */
    fun t(vararg args: Any?): String = String.format(java.util.Locale.ROOT, t, *args)
}

/** Выбранный язык хранится в настройках и переживает перезапуск. */
class Localization(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    init {
        val saved = prefs.getString("lang", null)
        CurrentLang = Lang.entries.firstOrNull { it.code == saved }
        // Языка ещё не выбирали — берём язык телефона, если он нам знаком.
            ?: Lang.entries.firstOrNull { it.code == java.util.Locale.getDefault().language }
            ?: Lang.RU
    }

    fun use(l: Lang) {
        CurrentLang = l
        prefs.edit().putString("lang", l.code).apply()
    }
}

/* ------------------------------------------------------------------ надписи */

object S {

    // --- заголовки экранов ---
    val navDevices = S7("Приборы", "Devices", "Geräte", "Appareils", "Dispositivos", "设备", "الأجهزة")
    val navFirmware = S7("Прошивка", "Firmware", "Firmware", "Micrologiciel", "Firmware", "固件", "البرنامج الثابت")
    val navFinds = S7("Находки", "Finds", "Funde", "Trouvailles", "Hallazgos", "发现", "المكتشفات")
    val navMap = S7("Карта", "Map", "Karte", "Carte", "Mapa", "地图", "الخريطة")
    val navAccess = S7("Доступ", "Access", "Zugang", "Accès", "Acceso", "访问", "الدخول")
    val navSettings = S7("Настройки", "Settings", "Einstellungen", "Réglages", "Ajustes", "设置", "الإعدادات")

    // --- состояние связи ---
    val stReady = S7("На связи", "Connected", "Verbunden", "Connecté", "Conectado", "已连接", "متصل")
    val stConnecting = S7("Подключаюсь", "Connecting", "Verbinde", "Connexion", "Conectando", "连接中", "جارٍ الاتصال")
    val stScanning = S7("Поиск", "Scanning", "Suche", "Recherche", "Buscando", "搜索中", "بحث")
    val stIdle = S7("Не подключён", "Offline", "Getrennt", "Hors ligne", "Sin conexión", "未连接", "غير متصل")

    // --- экран приборов ---
    val needPerm = S7(
        "Нужно разрешение на поиск Bluetooth-устройств",
        "Permission to scan for Bluetooth devices is required",
        "Berechtigung zur Bluetooth-Suche erforderlich",
        "L'autorisation de rechercher des appareils Bluetooth est requise",
        "Se necesita permiso para buscar dispositivos Bluetooth",
        "需要蓝牙扫描权限",
        "مطلوب إذن للبحث عن أجهزة البلوتوث",
    )
    val allow = S7("Разрешить", "Allow", "Erlauben", "Autoriser", "Permitir", "允许", "السماح")
    val searching = S7("Ищу приборы…", "Searching…", "Suche…", "Recherche…", "Buscando…", "正在搜索…", "جارٍ البحث…")
    val noneFound = S7(
        "Приборов рядом нет. Включите прибор и нажмите «Искать приборы».",
        "No devices nearby. Switch the detector on and tap “Search”.",
        "Keine Geräte in der Nähe. Gerät einschalten und „Suchen“ antippen.",
        "Aucun appareil à proximité. Allumez le détecteur et appuyez sur « Rechercher ».",
        "No hay dispositivos cerca. Encienda el detector y pulse «Buscar».",
        "附近没有设备。请打开探测器并点击「搜索」。",
        "لا توجد أجهزة قريبة. شغّل الجهاز واضغط «بحث».",
    )
    val stop = S7("Остановить", "Stop", "Stopp", "Arrêter", "Detener", "停止", "إيقاف")
    val searchDevices = S7("Искать приборы", "Search devices", "Geräte suchen", "Rechercher", "Buscar dispositivos", "搜索设备", "بحث عن الأجهزة")
    val language = S7("Язык", "Language", "Sprache", "Langue", "Idioma", "语言", "اللغة")

    // --- экран прибора ---
    val stepOf = S7("из %d · ступень", "of %d · step", "von %d · Stufe", "sur %d · niveau", "de %d · nivel", "共 %d 级", "من %d · درجة")
    val traceTitle = S7(
        "Проводка · последние 8 с", "Sweep · last 8 s", "Schwenk · letzte 8 s",
        "Balayage · 8 dernières s", "Barrido · últimos 8 s", "扫描 · 最近 8 秒", "المسح · آخر ٨ ثوانٍ",
    )
    val soundPhone = S7("Звук в телефоне", "Sound on phone", "Ton am Telefon", "Son sur le téléphone", "Sonido en el teléfono", "手机声音", "الصوت في الهاتف")
    val buzzTarget = S7("Вибрация на цель", "Vibrate on target", "Vibration bei Ziel", "Vibrer sur la cible", "Vibrar al detectar", "目标振动", "اهتزاز عند الهدف")
    val buzzNoMotor = S7("Вибрация (мотора нет)", "Vibration (no motor)", "Vibration (kein Motor)", "Vibration (pas de moteur)", "Vibración (sin motor)", "振动（无马达）", "اهتزاز (لا يوجد محرك)")
    val keepAwake = S7("Не гасить экран", "Keep screen on", "Bildschirm anlassen", "Garder l'écran allumé", "Mantener pantalla encendida", "保持屏幕常亮", "إبقاء الشاشة مضاءة")
    val markFind = S7("Отметить находку", "Mark a find", "Fund markieren", "Marquer une trouvaille", "Marcar hallazgo", "标记发现", "تسجيل اكتشاف")
    val recorded = S7("Записано: %s · %s", "Recorded: %s · %s", "Gespeichert: %s · %s", "Enregistré : %s · %s", "Registrado: %s · %s", "已记录：%s · %s", "تم التسجيل: %s · %s")
    val toDevices = S7("К приборам", "Devices", "Geräte", "Appareils", "Dispositivos", "设备", "الأجهزة")

    // --- телеметрия ---
    val teleTitle = S7("Телеметрия прибора", "Device telemetry", "Gerätetelemetrie", "Télémétrie", "Telemetría", "设备遥测", "قياسات الجهاز")
    val battery = S7("Аккумулятор", "Battery", "Akku", "Batterie", "Batería", "电池", "البطارية")
    val temperature = S7("Температура", "Temperature", "Temperatur", "Température", "Temperatura", "温度", "الحرارة")
    val heating = S7("Подогрев", "Heater", "Heizung", "Chauffage", "Calefacción", "加热", "التسخين")
    val power = S7("Питание", "Power", "Stromversorgung", "Alimentation", "Alimentación", "电源", "التغذية")
    val step = S7("Ступень", "Step", "Stufe", "Niveau", "Nivel", "级别", "الدرجة")
    val setpointsLbl = S7("Уставки", "Setpoints", "Sollwerte", "Consignes", "Consignas", "设定值", "القيم المضبوطة")
    val sensorSilent = S7("датчик молчит", "sensor silent", "Sensor stumm", "capteur muet", "sensor mudo", "传感器无响应", "المستشعر صامت")
    val board = S7("плата", "board", "Platine", "carte", "placa", "主板", "اللوحة")
    val pidHeats = S7("ПИД греет", "PID heating", "PID heizt", "PID chauffe", "PID calentando", "PID 加热中", "PID يسخّن")
    val pidIdle = S7("ПИД молчит", "PID idle", "PID inaktiv", "PID au repos", "PID inactivo", "PID 空闲", "PID خامل")
    val charging = S7("зарядка", "charging", "lädt", "en charge", "cargando", "充电中", "جارٍ الشحن")
    val onBattery = S7("от батареи", "on battery", "Akkubetrieb", "sur batterie", "con batería", "电池供电", "على البطارية")
    val usbIn = S7("USB подключён", "USB connected", "USB verbunden", "USB connecté", "USB conectado", "USB 已连接", "USB متصل")
    val usbOut = S7("USB не подключён", "USB not connected", "USB getrennt", "USB déconnecté", "USB desconectado", "USB 未连接", "USB غير متصل")
    val ofN = S7("из %d", "of %d", "von %d", "sur %d", "de %d", "共 %d", "من %d")
    val asInDevice = S7("как в приборе", "as in device", "wie im Gerät", "comme dans l'appareil", "como en el equipo", "与设备一致", "كما في الجهاز")

    // --- пароль ---
    val lockIntro = S7(
        "Настройки прибора закрыты паролем: неверные значения делителя или ПИД-регулятора сбивают показания и управление подогревом.",
        "Device settings are protected: wrong divider or PID values break the readings and the heater control.",
        "Die Geräteeinstellungen sind geschützt: falsche Teiler- oder PID-Werte verfälschen Messwerte und Heizungsregelung.",
        "Les réglages sont protégés : de mauvaises valeurs de diviseur ou de PID faussent les mesures et la régulation.",
        "Los ajustes están protegidos: valores erróneos del divisor o del PID falsean las lecturas y el control del calentador.",
        "设备设置受密码保护：分压系数或 PID 数值错误会破坏读数与加热控制。",
        "إعدادات الجهاز محمية بكلمة مرور: القيم الخاطئة للمقسّم أو PID تُفسد القراءات والتحكم بالتسخين.",
    )
    val password = S7("Пароль", "Password", "Passwort", "Mot de passe", "Contraseña", "密码", "كلمة المرور")
    val wrongPin = S7("Пароль не подходит", "Wrong password", "Falsches Passwort", "Mot de passe incorrect", "Contraseña incorrecta", "密码错误", "كلمة المرور غير صحيحة")
    val enter = S7("Войти", "Enter", "Öffnen", "Entrer", "Entrar", "进入", "دخول")
    val back = S7("Назад", "Back", "Zurück", "Retour", "Atrás", "返回", "رجوع")
    val backToDevice = S7("Назад к прибору", "Back to device", "Zurück zum Gerät", "Retour à l'appareil", "Volver al equipo", "返回设备", "العودة إلى الجهاز")

    // --- настройки ---
    val deviceSection = S7("Прибор", "Device", "Gerät", "Appareil", "Equipo", "设备", "الجهاز")
    val dividerHint = S7(
        "Делитель задаётся коэффициентом, умноженным на 1000: 1000 — это ×1.000. Коэффициенты ПИД — умноженные на 100: 250 — это 2.50.",
        "The divider is a ratio times 1000: 1000 means ×1.000. PID gains are times 100: 250 means 2.50.",
        "Der Teiler ist ein Faktor mal 1000: 1000 bedeutet ×1,000. PID-Beiwerte mal 100: 250 bedeutet 2,50.",
        "Le diviseur est un rapport ×1000 : 1000 signifie ×1,000. Les gains PID sont ×100 : 250 signifie 2,50.",
        "El divisor es una relación ×1000: 1000 significa ×1,000. Las ganancias PID son ×100: 250 significa 2,50.",
        "分压系数以 ×1000 表示：1000 即 ×1.000。PID 系数以 ×100 表示：250 即 2.50。",
        "المقسّم معامل مضروب في ١٠٠٠: القيمة 1000 تعني ×1.000. معاملات PID مضروبة في ١٠٠: القيمة 250 تعني 2.50.",
    )
    val fieldDivider = S7("Делитель напряжения x1000", "Voltage divider x1000", "Spannungsteiler x1000", "Diviseur de tension x1000", "Divisor de tensión x1000", "分压系数 x1000", "مقسّم الجهد ×1000")
    val fieldTemp = S7("Уставка подогрева, °C", "Heater setpoint, °C", "Heizungs-Sollwert, °C", "Consigne de chauffage, °C", "Consigna del calentador, °C", "加热设定值 °C", "قيمة التسخين المضبوطة، °م")
    val readBtn = S7("Прочитать", "Read", "Lesen", "Lire", "Leer", "读取", "قراءة")
    val writeBtn = S7("Записать", "Write", "Schreiben", "Écrire", "Escribir", "写入", "كتابة")
    val noCfg = S7(
        "Прибор не прислал свои настройки. В прошивке, которая сейчас стоит, разбора команд DIV/PID/TSP ещё нет — обновите её, и поля станут рабочими.",
        "The device did not report its settings. The firmware it runs does not understand DIV/PID/TSP yet — update it and the fields will work.",
        "Das Gerät hat keine Einstellungen gemeldet. Die installierte Firmware kennt DIV/PID/TSP noch nicht — nach dem Update funktionieren die Felder.",
        "L'appareil n'a pas renvoyé ses réglages. Le micrologiciel installé ne connaît pas encore DIV/PID/TSP — mettez-le à jour.",
        "El equipo no envió sus ajustes. El firmware instalado aún no entiende DIV/PID/TSP: actualícelo y los campos funcionarán.",
        "设备未返回设置。当前固件尚不支持 DIV/PID/TSP 命令，升级后这些字段即可使用。",
        "لم يرسل الجهاز إعداداته. البرنامج الثابت الحالي لا يفهم أوامر DIV/PID/TSP بعد — حدّثه لتعمل الحقول.",
    )
    val cfgSent = S7(
        "Отправлено. Прибор должен ответить строкой настроек.",
        "Sent. The device should reply with its settings.",
        "Gesendet. Das Gerät sollte mit seinen Einstellungen antworten.",
        "Envoyé. L'appareil doit répondre avec ses réglages.",
        "Enviado. El equipo debe responder con sus ajustes.",
        "已发送。设备应回复其设置。",
        "تم الإرسال. يجب أن يردّ الجهاز بإعداداته.",
    )
    val nowShows = S7("Что прибор показывает сейчас", "What the device reports now", "Aktuelle Messwerte", "Mesures actuelles", "Lecturas actuales", "设备当前读数", "ما يعرضه الجهاز الآن")
    val voltage = S7("Напряжение на АКБ", "Battery voltage", "Akkuspannung", "Tension batterie", "Tensión de batería", "电池电压", "جهد البطارية")
    val chargeLbl = S7("Заряд", "Charge", "Ladung", "Charge", "Carga", "电量", "الشحن")
    val appearance = S7("Оформление приложения", "App appearance", "Erscheinungsbild", "Apparence", "Apariencia", "应用外观", "مظهر التطبيق")

    // --- прошивка ---
    val fwModel = S7("Модель", "Model", "Modell", "Modèle", "Modelo", "型号", "الطراز")
    val fwSerial = S7("Серийный номер", "Serial number", "Seriennummer", "Numéro de série", "Número de serie", "序列号", "الرقم التسلسلي")
    val fwInDevice = S7("Версия в приборе", "Version in device", "Version im Gerät", "Version dans l'appareil", "Versión en el equipo", "设备内版本", "الإصدار في الجهاز")
    val fwInApp = S7("Версия приложения", "App version", "App-Version", "Version de l'app", "Versión de la app", "应用版本", "إصدار التطبيق")
    val fwAvailable = S7("Доступна", "Available", "Verfügbar", "Disponible", "Disponible", "可用版本", "المتاح")
    val fwPressCheck = S7("нажмите «Проверить»", "tap “Check”", "„Prüfen“ antippen", "appuyez sur « Vérifier »", "pulse «Comprobar»", "点击「检查」", "اضغط «فحص»")
    val fwChecking = S7("проверяю…", "checking…", "prüfe…", "vérification…", "comprobando…", "检查中…", "جارٍ الفحص…")
    val fwNoBuild = S7("нет сборки для этой модели", "no build for this model", "kein Build für dieses Modell", "aucune version pour ce modèle", "no hay versión para este modelo", "该型号无可用版本", "لا يوجد إصدار لهذا الطراز")
    val fwNewer = S7("%s · новее", "%s · newer", "%s · neuer", "%s · plus récent", "%s · más nueva", "%s · 更新", "%s · أحدث")
    val fwSame = S7("%s · уже стоит", "%s · already installed", "%s · bereits installiert", "%s · déjà installé", "%s · ya instalada", "%s · 已安装", "%s · مثبّت بالفعل")
    val fwCheck = S7("Проверить", "Check", "Prüfen", "Vérifier", "Comprobar", "检查", "فحص")
    val fwUpdate = S7("Обновить", "Update", "Aktualisieren", "Mettre à jour", "Actualizar", "更新", "تحديث")
    val fwFound = S7("Найдена %s-%s.bin, %d КБ", "Found %s-%s.bin, %d KB", "Gefunden %s-%s.bin, %d KB", "Trouvé %s-%s.bin, %d Ko", "Encontrado %s-%s.bin, %d KB", "找到 %s-%s.bin，%d KB", "تم العثور على %s-%s.bin، %d ك.ب")
    val fwDownloading = S7("Скачиваю прошивку…", "Downloading firmware…", "Lade Firmware…", "Téléchargement…", "Descargando…", "正在下载固件…", "جارٍ تنزيل البرنامج الثابت…")
    val fwDownloadFail = S7("Не удалось скачать файл", "Download failed", "Download fehlgeschlagen", "Échec du téléchargement", "Error al descargar", "下载失败", "فشل التنزيل")
    val fwReceived = S7("Получено %d байт, передаю в прибор", "Got %d bytes, sending to device", "%d Bytes erhalten, sende an Gerät", "%d octets reçus, envoi à l'appareil", "%d bytes recibidos, enviando", "已获取 %d 字节，正在发送", "تم استلام %d بايت، جارٍ الإرسال")
    val fwDeviceSays = S7("Прибор: %s", "Device: %s", "Gerät: %s", "Appareil : %s", "Equipo: %s", "设备：%s", "الجهاز: %s")
    val fwTransfer = S7("Передача: %s", "Transfer: %s", "Übertragung: %s", "Transfert : %s", "Transferencia: %s", "传输：%s", "النقل: %s")
    val fwWritten = S7("Образ записан.", "Image written.", "Abbild geschrieben.", "Image écrite.", "Imagen escrita.", "镜像已写入。", "تمت كتابة النسخة.")
    val fwWaitReboot = S7("Жду перезагрузки прибора…", "Waiting for the device to restart…", "Warte auf Neustart…", "Attente du redémarrage…", "Esperando el reinicio…", "等待设备重启…", "بانتظار إعادة تشغيل الجهاز…")
    val fwDone = S7("Готово. В приборе %s.", "Done. Device runs %s.", "Fertig. Gerät läuft mit %s.", "Terminé. L'appareil est en %s.", "Listo. El equipo tiene %s.", "完成。设备版本为 %s。", "تم. الجهاز يعمل بالإصدار %s.")
    val fwNoAnswer = S7(
        "Прибор не отозвался после перезагрузки. Включите на нём Bluetooth четырьмя нажатиями и подключитесь заново.",
        "No answer after the restart. Turn Bluetooth on with four presses and connect again.",
        "Keine Antwort nach dem Neustart. Bluetooth mit vier Tastendrücken einschalten und erneut verbinden.",
        "Aucune réponse après le redémarrage. Activez le Bluetooth par quatre appuis et reconnectez-vous.",
        "Sin respuesta tras el reinicio. Active el Bluetooth con cuatro pulsaciones y conéctese de nuevo.",
        "重启后无响应。请按四次开启蓝牙并重新连接。",
        "لا استجابة بعد إعادة التشغيل. شغّل البلوتوث بأربع ضغطات ثم أعد الاتصال.",
    )
    val fwNoPassport = S7(
        "Связь восстановлена, но прибор не прислал паспорт.",
        "Reconnected, but the device sent no identity line.",
        "Verbunden, aber das Gerät sendete keine Kennung.",
        "Reconnecté, mais l'appareil n'a pas envoyé son identité.",
        "Reconectado, pero el equipo no envió su identificación.",
        "已重新连接，但设备未发送标识。",
        "تمت إعادة الاتصال، لكن الجهاز لم يرسل بطاقته.",
    )
    val fwMismatch = S7(
        "Прибор сообщает версию %s вместо %s. Либо образ не встал, либо в файле прошивки версия не поднята.",
        "The device reports %s instead of %s. Either the image did not take, or the version inside the firmware was not raised.",
        "Das Gerät meldet %s statt %s. Entweder wurde das Abbild nicht übernommen, oder die Version in der Firmware wurde nicht erhöht.",
        "L'appareil annonce %s au lieu de %s. Soit l'image n'a pas été prise, soit la version dans le micrologiciel n'a pas été incrémentée.",
        "El equipo informa %s en lugar de %s. O la imagen no se aplicó, o la versión del firmware no se incrementó.",
        "设备报告版本 %s 而不是 %s。要么镜像未生效，要么固件中的版本号未提升。",
        "يُبلّغ الجهاز عن الإصدار %s بدل %s. إمّا أن النسخة لم تُطبَّق، وإمّا أن الإصدار داخل البرنامج الثابت لم يُرفع.",
    )
    val fwPowerWarn = S7(
        "Питание при обновлении не отключайте. Пока новый образ не записан целиком, прибор работает на старом — обрыв придётся начинать заново.",
        "Do not cut the power while updating. Until the new image is written in full the device runs the old one, and a break means starting over.",
        "Während des Updates die Stromversorgung nicht trennen. Bis das neue Abbild vollständig geschrieben ist, läuft das alte — ein Abbruch bedeutet Neubeginn.",
        "Ne coupez pas l'alimentation pendant la mise à jour. Tant que la nouvelle image n'est pas écrite en entier, l'appareil garde l'ancienne.",
        "No corte la alimentación durante la actualización. Hasta que la nueva imagen se escriba por completo, el equipo usa la anterior.",
        "升级过程中请勿断电。新镜像未完整写入前设备仍运行旧版本，中断需从头开始。",
        "لا تفصل التغذية أثناء التحديث. حتى تُكتب النسخة الجديدة بالكامل يعمل الجهاز بالقديمة، والانقطاع يعني البدء من جديد.",
    )

    // --- находки ---
    val findsEmpty = S7(
        "Пока пусто. Флажок ставится сам, когда на шкале больше %d делений. Кнопка «Отметить находку» делает то же вручную.",
        "Empty so far. A flag is dropped automatically above %d bars. The “Mark a find” button does the same by hand.",
        "Noch leer. Ab %d Balken wird automatisch eine Fahne gesetzt. „Fund markieren“ macht dasselbe von Hand.",
        "Vide pour l'instant. Un drapeau est posé automatiquement au-delà de %d barres.",
        "Vacío por ahora. La bandera se coloca automáticamente por encima de %d barras.",
        "目前为空。刻度超过 %d 格时会自动插旗。",
        "فارغ حتى الآن. تُوضع العلامة تلقائيًا فوق %d درجات.",
    )
    val mapWithFlags = S7("Карта с флажками", "Map with flags", "Karte mit Fahnen", "Carte avec drapeaux", "Mapa con banderas", "带标记的地图", "الخريطة مع العلامات")
    val clearFlags = S7("Стереть флажки", "Clear flags", "Fahnen löschen", "Effacer les drapeaux", "Borrar banderas", "清除标记", "مسح العلامات")
    val autoTag = S7("авто", "auto", "auto", "auto", "auto", "自动", "تلقائي")

    // --- названия уставок ---
    val ctlSensitivity = S7("Чувствительность", "Sensitivity", "Empfindlichkeit", "Sensibilité", "Sensibilidad", "灵敏度", "الحساسية")
    val ctlVolume = S7("Громкость", "Volume", "Lautstärke", "Volume", "Volumen", "音量", "مستوى الصوت")
    val ctlGround = S7("Баланс грунта", "Ground balance", "Bodenabgleich", "Équilibrage du sol", "Balance de suelo", "地平衡", "موازنة التربة")

    // --- мелочи списка и карты ---
    val serialShort = S7("№ %s", "SN %s", "Nr. %s", "N° %s", "N.º %s", "编号 %s", "رقم %s")
    val fwShort = S7("ПО %s", "FW %s", "FW %s", "FW %s", "FW %s", "固件 %s", "برنامج %s")
    val hereYouAre = S7("Вы здесь", "You are here", "Sie sind hier", "Vous êtes ici", "Está aquí", "您在此处", "أنت هنا")
    val pinStep = S7("%s · ступень %d", "%s · step %d", "%s · Stufe %d", "%s · niveau %d", "%s · nivel %d", "%s · 级别 %d", "%s · درجة %d")
    val noPoints = S7(
        "Нет ни одной точки с координатами", "No points with coordinates yet",
        "Noch keine Punkte mit Koordinaten", "Aucun point avec coordonnées",
        "Aún no hay puntos con coordenadas", "尚无带坐标的点", "لا توجد نقاط بإحداثيات",
    )
    val ringIs = S7("кольцо = %d м", "ring = %d m", "Ring = %d m", "anneau = %d m", "anillo = %d m", "圆环 = %d 米", "الحلقة = %d م")

    // --- карта ---
    val schemeBtn = S7("Схема", "Sketch", "Skizze", "Schéma", "Esquema", "示意图", "مخطط")
    val flagsCount = S7("Флажков: %d", "Flags: %d", "Fahnen: %d", "Drapeaux : %d", "Banderas: %d", "标记：%d", "العلامات: %d")
    val noCoords = S7("без координат: %d", "without coordinates: %d", "ohne Koordinaten: %d", "sans coordonnées : %d", "sin coordenadas: %d", "无坐标：%d", "بدون إحداثيات: %d")
    val noGeo = S7(
        "Нет доступа к геолокации — флажки ставиться не будут.",
        "No location access — flags will not be placed.",
        "Kein Standortzugriff — es werden keine Fahnen gesetzt.",
        "Pas d'accès à la localisation — aucun drapeau ne sera posé.",
        "Sin acceso a la ubicación: no se colocarán banderas.",
        "无定位权限，将不会插旗。",
        "لا صلاحية للموقع — لن تُوضع العلامات.",
    )
    val clearTitle = S7("Стереть все флажки?", "Clear all flags?", "Alle Fahnen löschen?", "Effacer tous les drapeaux ?", "¿Borrar todas las banderas?", "清除所有标记？", "مسح كل العلامات؟")
    val clearText = S7(
        "Будут удалены все %d точек вместе с координатами. Отменить нельзя.",
        "All %d points and their coordinates will be deleted. This cannot be undone.",
        "Alle %d Punkte samt Koordinaten werden gelöscht. Nicht rückgängig zu machen.",
        "Les %d points et leurs coordonnées seront supprimés. Irréversible.",
        "Se eliminarán los %d puntos con sus coordenadas. No se puede deshacer.",
        "将删除全部 %d 个点及其坐标，且无法撤销。",
        "سيتم حذف كل النقاط %d مع إحداثياتها. لا يمكن التراجع.",
    )
    val clearOk = S7("Стереть", "Delete", "Löschen", "Supprimer", "Borrar", "删除", "حذف")
    val cancel = S7("Отмена", "Cancel", "Abbrechen", "Annuler", "Cancelar", "取消", "إلغاء")
}
