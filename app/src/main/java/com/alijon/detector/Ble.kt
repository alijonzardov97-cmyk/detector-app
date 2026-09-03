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
import kotlinx.coroutines.flow.StateFlow
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

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var textBuf = StringBuilder()

    /** Replies to OTA commands land here so the transfer can await them. */
    private var otaReply: CompletableDeferred<String>? = null
    private var writeAck: CompletableDeferred<Boolean>? = null

    fun bluetoothOn(): Boolean = adapter?.isEnabled == true

    // ------------------------------------------------------------------ scan
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val rec = r.scanRecord
            // Service data holds "MODEL|SERIAL|FW" so the list is informative
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
            g.setCharacteristicNotification(tx, true)
            tx.getDescriptor(Proto.CCCD)?.let { d ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(d)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            link.value = Link.READY
            send("ID?")
        }

        // Android 13+ signature
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) = onIncoming(value)

        @Suppress("DEPRECATION")
        @Deprecated("Kept for Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            onIncoming(c.value ?: return)
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
        gatt = null; rx = null; textBuf = StringBuilder()
        identity.value = null
        link.value = Link.IDLE
    }

    // -------------------------------------------------------------- incoming
    private fun onIncoming(bytes: ByteArray) {
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
        if (line.startsWith("OTA")) { otaReply?.complete(line); otaReply = null; return }
        val keys = Models.of(identity.value?.model).controls.map { it.key }
        telemetry.value = Parser.telemetry(line, telemetry.value, keys)
    }

    // -------------------------------------------------------------- outgoing
    private fun writeRaw(bytes: ByteArray, withResponse: Boolean): Boolean {
        val g = gatt ?: return false
        val c = rx ?: return false
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run { c.writeType = type; c.value = bytes; g.writeCharacteristic(c) }
        }
    }

    fun send(cmd: String) { writeRaw((cmd + "\n").toByteArray(Charsets.US_ASCII), true) }

    /**
     * Пишет один кусок и ждёт подтверждения контроллера.
     *
     * Прошлое ожидание, забытое по таймауту, обязательно снимаем: иначе
     * запоздавший колбэк завершил бы уже СЛЕДУЮЩЕЕ ожидание, и передача
     * рассыпалась бы дальше по цепочке.
     */
    private suspend fun writeChunk(bytes: ByteArray): Boolean {
        writeAck?.let { it.complete(false); writeAck = null }

        val ack = CompletableDeferred<Boolean>()
        writeAck = ack
        if (!writeRaw(bytes, true)) { writeAck = null; return false }

        // Приём куска на приборе может упереться в запись страницы флеша, а это
        // десятки миллисекунд. Пять секунд — с большим запасом.
        val ok = withTimeoutOrNull(5000) { ack.await() } ?: false
        if (!ok) writeAck = null
        return ok
    }

    private suspend fun awaitOta(timeoutMs: Long): String? {
        val d = CompletableDeferred<String>()
        otaReply = d
        return withTimeoutOrNull(timeoutMs) { d.await() }
    }

    /**
     * Streams a firmware image. Reports progress 0..1 and returns null on
     * success or a human-readable reason on failure. Interrupting it is safe:
     * the device keeps running the old image until the new one is complete.
     */
    suspend fun sendFirmware(image: ByteArray, onProgress: (Float) -> Unit): String? {
        if (link.value != Link.READY) return "прибор не подключён"

        send("OTA ${image.size}")
        val hello = awaitOta(5000) ?: return "прибор не ответил на запрос обновления"
        if (!hello.startsWith("OTAOK")) return hello

        val chunk = (mtu - 3).coerceIn(20, 512)
        var off = 0
        while (off < image.size) {
            if (link.value != Link.READY) return "связь с прибором прервалась на $off байте"

            val end = minOf(off + chunk, image.size)
            val part = image.copyOfRange(off, end)

            // Одиночный сбой куска — не повод бросать всю передачу: пробуем
            // ещё дважды с короткой паузой. Прибор принимает по счётчику байт,
            // так что повтор того же куска ему безразличен.
            var sent = false
            for (attempt in 1..3) {
                if (writeChunk(part)) { sent = true; break }
                delay(120L * attempt)
            }
            if (!sent) return "передача оборвалась на $off байте"

            off = end
            onProgress(off.toFloat() / image.size)
            if (off % (chunk * 32) == 0) delay(2)   // дать стеку выдохнуть
        }

        send("OTA!")
        val fin = awaitOta(30000) ?: return "прибор не подтвердил запись"
        return if (fin.startsWith("OTADONE")) null else fin
    }
}
