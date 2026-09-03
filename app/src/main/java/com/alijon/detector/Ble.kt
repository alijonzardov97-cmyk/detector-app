package com.alijon.detector

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** A detector seen in the air. Model and firmware come from advertising data. */
data class Found(
    val address: String,
    val name: String?,
    val rssi: Int,
    val model: String?,
    val serial: String?,
    val fw: String?,
)

enum class Link { IDLE, SCANNING, CONNECTING, READY }

@SuppressLint("MissingPermission")
class DetectorBle(private val ctx: Context) {

    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    val link = MutableStateFlow(Link.IDLE)
    val found = MutableStateFlow<List<Found>>(emptyList())
    val identity = MutableStateFlow<Identity?>(null)
    val telemetry = MutableStateFlow(Telemetry())

    /** Служебные сообщения об обновлении — для журнала на экране. */
    val otaNote = MutableStateFlow<String?>(null)

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var textBuf = StringBuilder()

    /*
     * Характеристики сервиса обновления (библиотека BLEOTA на приборе).
     * Их отсутствие означает старую прошивку — обновлять по воздуху нечем.
     */
    private var otaRecv: BluetoothGattCharacteristic? = null
    private var otaCmd: BluetoothGattCharacteristic? = null

    fun bluetoothOn(): Boolean = adapter?.isEnabled == true

    /** Прибор умеет принимать прошивку по воздуху. */
    fun otaSupported(): Boolean = otaRecv != null && otaCmd != null

    // ------------------------------------------------------------------ scan
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val rec = r.scanRecord
            // Service data holds "MODEL|SERIAL" so the list is informative
            // before we ever connect. Absent on a firmware that does not send it.
            val raw = rec?.getServiceData(ParcelUuid(Proto.SERVICE))
                ?.toString(Charsets.US_ASCII)?.split("|")
            val item = Found(
                address = r.device.address,
                name = rec?.deviceName,
                rssi = r.rssi,
                model = raw?.getOrNull(0),
                serial = raw?.getOrNull(1),
                fw = raw?.getOrNull(2),
            )
            found.value = (found.value.filter { it.address != item.address } + item)
                .sortedByDescending { it.rssi }
        }
    }

    fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        found.value = emptyList()
        link.value = Link.SCANNING
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Proto.SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCb
        )
    }

    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCb)
        if (link.value == Link.SCANNING) link.value = Link.IDLE
    }

    // --------------------------------------------------------------- connect
    /*
     * Уведомления включаются ПО ОДНОМУ.
     *
     * Android держит в полёте ровно одну операцию с GATT: пока не пришёл
     * onDescriptorWrite, следующая запись дескриптора просто теряется. Их у нас
     * теперь три — телеметрия и две характеристики обновления, — поэтому
     * очередь, а не три вызова подряд.
     */
    private val toSubscribe = ArrayDeque<BluetoothGattCharacteristic>()

    private fun subscribeNext(g: BluetoothGatt) {
        val c = toSubscribe.removeFirstOrNull()
        if (c == null) {
            link.value = Link.READY
            send("ID?")
            return
        }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(Proto.CCCD)
        if (d == null) { subscribeNext(g); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private val gattCb = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.requestMtu(517)                 // large writes make OTA fast
            } else {
                cleanup()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            this@DetectorBle.mtu = mtu
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(Proto.SERVICE) ?: run { cleanup(); return }
            rx = svc.getCharacteristic(Proto.RX)
            val tx = svc.getCharacteristic(Proto.TX) ?: run { cleanup(); return }

            val ota = g.getService(Proto.OTA_SERVICE)
            otaRecv = ota?.getCharacteristic(Proto.OTA_RECV)
            otaCmd = ota?.getCharacteristic(Proto.OTA_CMD)

            toSubscribe.clear()
            toSubscribe.addLast(tx)
            otaRecv?.let { toSubscribe.addLast(it) }
            otaCmd?.let { toSubscribe.addLast(it) }
            subscribeNext(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            subscribeNext(g)
        }

        // Android 13+ signature
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) = onIncoming(c.uuid, value)

        @Suppress("DEPRECATION")
        @Deprecated("Kept for Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            onIncoming(c.uuid, c.value ?: return)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
            writeAck = null
        }
    }

    var mtu: Int = 23
        private set

    /** Адрес последнего прибора — чтобы переподключиться после его перезагрузки. */
    var lastAddress: String? = null
        private set

    fun connect(address: String) {
        stopScan()
        val dev = adapter?.getRemoteDevice(address) ?: return
        lastAddress = address
        link.value = Link.CONNECTING
        gatt = dev.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    /** Переподключиться к тому же прибору, не трогая состояние передачи. */
    private suspend fun reconnect(timeoutMs: Long = 12000): Boolean {
        val addr = lastAddress ?: return false
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null; rx = null; otaRecv = null; otaCmd = null
        delay(600)
        connect(addr)
        return withTimeoutOrNull(timeoutMs) {
            while (link.value != Link.READY) delay(120)
            true
        } ?: false
    }

    /**
     * Дождаться, пока прибор перезагрузится после обновления, и подключиться
     * снова. Без этого паспорт остаётся прочитанным ДО перезагрузки, и на
     * экране висит старая версия — прибор-то уже другой.
     */
    suspend fun reconnectAfterUpdate(timeoutMs: Long = 25000): Boolean {
        val addr = lastAddress ?: return false
        disconnect()
        delay(3000)                       // дать прибору подняться и начать рекламу

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            connect(addr)
            val ok = withTimeoutOrNull(8000) {
                while (identity.value == null) delay(150)
                true
            } ?: false
            if (ok) return true
            disconnect()
            delay(1500)
        }
        return false
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        cleanup()
    }

    private fun cleanup() {
        gatt = null; rx = null; otaRecv = null; otaCmd = null
        textBuf = StringBuilder()
        toSubscribe.clear()
        identity.value = null
        link.value = Link.IDLE
    }

    // -------------------------------------------------------------- incoming
    private fun onIncoming(uuid: java.util.UUID, bytes: ByteArray) {
        when (uuid) {
            Proto.OTA_RECV -> { onFwAnswer(bytes); return }
            Proto.OTA_CMD -> { onCmdAnswer(bytes); return }
        }
        textBuf.append(String(bytes, Charsets.US_ASCII))
        while (true) {
            val i = textBuf.indexOf("\n")
            if (i < 0) break
            val line = textBuf.substring(0, i).trim()
            textBuf.delete(0, i + 1)
            if (line.isNotEmpty()) handleLine(line)
        }
        if (textBuf.length > 512) textBuf = StringBuilder()   // rubbish guard
    }

    private fun handleLine(line: String) {
        Parser.identity(line)?.let { identity.value = it; return }
        val keys = Models.of(identity.value?.model).controls.map { it.key }
        telemetry.value = Parser.telemetry(line, telemetry.value, keys)
    }

    // -------------------------------------------------------------- outgoing
    private var writeAck: CompletableDeferred<Boolean>? = null

    private fun writeRaw(
        c: BluetoothGattCharacteristic, bytes: ByteArray, withResponse: Boolean
    ): Boolean {
        val g = gatt ?: return false
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { c.writeType = type; c.value = bytes; g.writeCharacteristic(c) }
        }
    }

    fun send(cmd: String) {
        val c = rx ?: return
        writeRaw(c, (cmd + "\n").toByteArray(Charsets.US_ASCII), true)
    }

    /**
     * Пишет один пакет и ждёт, пока контроллер заберёт его у себя.
     *
     * Даже для записи «без ответа» Android вызывает onCharacteristicWrite —
     * когда освободился буфер. Без этого ожидания пакеты уходят быстрее, чем
     * стек успевает их отправлять, и часть просто теряется по дороге.
     *
     * Прошлое ожидание, забытое по таймауту, обязательно снимаем: иначе
     * запоздавший колбэк завершил бы уже СЛЕДУЮЩЕЕ ожидание, и передача
     * рассыпалась бы дальше по цепочке.
     */
    private suspend fun writeChunk(
        c: BluetoothGattCharacteristic, bytes: ByteArray, withResponse: Boolean
    ): Boolean {
        writeAck?.let { it.complete(false); writeAck = null }

        val ack = CompletableDeferred<Boolean>()
        writeAck = ack
        if (!writeRaw(c, bytes, withResponse)) { writeAck = null; return false }

        val ok = withTimeoutOrNull(5000) { ack.await() } ?: false
        if (!ok) writeAck = null
        return ok
    }

    // ------------------------------------------------------------------- OTA
    /*
     * Протокол обновления — тот же, что у библиотеки BLEOTA на приборе и у
     * фирменных решений Espressif. Здесь он повторён буква в букву, менять
     * что-либо в отрыве от прошивки нельзя.
     *
     * Команда (характеристика 0x8022), ровно 20 байт:
     *      [0..1] код команды      [2..17] полезная часть      [18..19] CRC16
     *      0x0001 начать (в полезной части — размер образа, 4 байта)
     *      0x0002 закончить
     *      0x0003 ответ прибора: [2..3] на какую команду, [4..5] 0 — принято
     *
     * Данные (характеристика 0x8020): образ режется на секторы по 4096 байт,
     * сектор — на пакеты по (MTU-8) байт:
     *      [0..1] номер сектора    [2] номер пакета    [3..] тело
     * У последнего пакета сектора номер = 0xFF, а в хвосте два байта CRC16
     * всего сектора. Прибор отвечает уведомлением [0..1] сектор [2..3] код:
     *      0 принято, 1 ошибка CRC, 2 не тот сектор, 3 длина, 5 не запущено.
     *
     * Следующий сектор отправляется ТОЛЬКО после подтверждения предыдущего.
     * Это и есть главное: пока прибор пишет страницу флеша, в эфире тишина,
     * и терять нечего.
     */
    private var fwAck: CompletableDeferred<Pair<Int, Int>>? = null
    private var cmdAck: CompletableDeferred<Pair<Int, Int>>? = null

    private fun le16(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun onFwAnswer(b: ByteArray) {
        if (b.size < 4) return
        val w = fwAck ?: return
        fwAck = null
        w.complete(le16(b, 0) to le16(b, 2))
    }

    private fun onCmdAnswer(b: ByteArray) {
        if (b.size < 6) return
        if (le16(b, 0) != CMD_ACK) return
        val w = cmdAck ?: return
        cmdAck = null
        w.complete(le16(b, 2) to le16(b, 4))
    }

    /** CRC-16/CCITT, полином 0x1021, начальное значение 0. Как в прошивке. */
    private fun crc16(data: ByteArray, from: Int, len: Int, init: Int): Int {
        var c = init
        for (i in from until from + len) {
            c = c xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF
                else (c shl 1) and 0xFFFF
            }
        }
        return c and 0xFFFF
    }

    private fun buildCmd(id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val b = ByteArray(20)
        b[0] = (id and 0xFF).toByte()
        b[1] = ((id shr 8) and 0xFF).toByte()
        for (i in payload.indices) if (i < 16) b[2 + i] = payload[i]
        val c = crc16(b, 0, 18, 0)
        b[18] = (c and 0xFF).toByte()
        b[19] = ((c shr 8) and 0xFF).toByte()
        return b
    }

    private suspend fun sendCommand(
        c: BluetoothGattCharacteristic, id: Int, payload: ByteArray, timeoutMs: Long
    ): Pair<Int, Int>? {
        // Ожидание ставим ДО отправки: ответ приходит в своём потоке и вполне
        // может успеть раньше, чем мы вернёмся из записи.
        val ack = CompletableDeferred<Pair<Int, Int>>()
        cmdAck = ack
        if (!writeChunk(c, buildCmd(id, payload), false)) { cmdAck = null; return null }
        val r = withTimeoutOrNull(timeoutMs) { ack.await() }
        if (r == null) cmdAck = null
        return r
    }

    /**
     * Отправляет образ прошивки. Прогресс 0..1, результат — null при успехе
     * или причина по-русски. Прерывать безопасно: прибор продолжает работать
     * на старой прошивке, пока новая не записана целиком и не проверена.
     */
    suspend fun sendFirmware(image: ByteArray, onProgress: (Float) -> Unit): String? {
        if (link.value != Link.READY) return "прибор не подключён"
        val recv = otaRecv
        val cmd = otaCmd
        if (recv == null || cmd == null)
            return "в приборе старая прошивка без сервиса обновления — " +
                    "один раз прошейте его по кабелю, дальше пойдёт по воздуху"

        otaNote.value = null

        val size = image.size
        val sizeLe = byteArrayOf(
            (size and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(),
            ((size shr 16) and 0xFF).toByte(),
            ((size shr 24) and 0xFF).toByte(),
        )

        val hello = sendCommand(cmd, CMD_FLASH, sizeLe, 15000)
            ?: return "прибор не ответил на запрос обновления"
        if (hello.second != 0) return "прибор отказался начинать обновление (код ${hello.second})"

        /*
         * Размер пакета: MTU минус три байта ATT, минус три байта заголовка и
         * минус два байта CRC у последнего пакета сектора. Итог не должен
         * превышать 512 — столько составляет предел длины характеристики.
         */
        val payload = (mtu - 8).coerceIn(16, 500)
        var off = 0
        var retriesTotal = 0

        while (off < size) {
            val secIdx = off / SECTOR
            val end = minOf(off + SECTOR, size)
            var done = false

            for (attempt in 0 until 3) {
                if (link.value != Link.READY) {
                    otaNote.value = "восстанавливаю связь на $off байте"
                    if (!reconnect()) { delay(800); continue }
                    delay(400)
                }

                // Ожидание подтверждения сектора ставим до отправки пакетов.
                val ack = CompletableDeferred<Pair<Int, Int>>()
                fwAck = ack

                var sentAll = true
                var p = off
                var seq = 0
                var crc = 0
                while (p < end) {
                    val q = minOf(p + payload, end)
                    crc = crc16(image, p, q - p, crc)
                    val last = q >= end
                    val pkt = ByteArray(3 + (q - p) + if (last) 2 else 0)
                    pkt[0] = (secIdx and 0xFF).toByte()
                    pkt[1] = ((secIdx shr 8) and 0xFF).toByte()
                    pkt[2] = if (last) 0xFF.toByte() else (seq and 0xFF).toByte()
                    System.arraycopy(image, p, pkt, 3, q - p)
                    if (last) {
                        pkt[3 + (q - p)] = (crc and 0xFF).toByte()
                        pkt[4 + (q - p)] = ((crc shr 8) and 0xFF).toByte()
                    }
                    if (!writeChunk(recv, pkt, false)) { sentAll = false; break }
                    p = q
                    seq++
                }

                if (!sentAll) { fwAck = null; retriesTotal++; delay(250); continue }

                val r = withTimeoutOrNull(15000) { ack.await() }
                fwAck = null

                if (r == null) { retriesTotal++; delay(250); continue }
                if (r.second == 0) { done = true; break }

                /*
                 * Код 2 — «жду другой сектор». Так бывает, когда подтверждение
                 * потерялось по дороге: прибор этот сектор уже записал и ушёл
                 * вперёд. Повторять его бессмысленно — идём дальше.
                 */
                if (r.second == 2) {
                    otaNote.value = "сектор $secIdx уже был принят, продолжаю"
                    done = true
                    break
                }
                retriesTotal++
                delay(250)
            }

            if (!done) return "сектор $secIdx не принят прибором, остановился на $off байте"
            off = end
            onProgress(off.toFloat() / size)
        }

        if (retriesTotal > 0) otaNote.value = "повторов секторов: $retriesTotal"

        val fin = sendCommand(cmd, CMD_STOP, ByteArray(0), 90000)
            ?: return "прибор не подтвердил запись образа"
        return when (fin.second) {
            0 -> null
            3 -> "прибор отверг подпись образа"
            else -> "прибор не принял образ (код ${fin.second})"
        }
    }

    private companion object {
        const val SECTOR = 4096
        const val CMD_FLASH = 0x0001
        const val CMD_STOP = 0x0002
        const val CMD_ACK = 0x0003
    }
}
