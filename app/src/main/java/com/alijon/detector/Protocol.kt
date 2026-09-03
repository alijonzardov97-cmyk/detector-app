package com.alijon.detector

import java.util.UUID

/*
 * PROTOCOL - shared by the firmware, the web app and this app.
 *
 * BLE, Nordic UART service:
 *   service  6e400001-b5a3-f393-e0a9-e50e24dcca9e
 *   TX  ...0003  notify   device -> phone
 *   RX  ...0002  write    phone  -> device
 *
 * The device ADVERTISES service data under the UART UUID so the scan list can
 * show model, serial and firmware without connecting:
 *   "PI-1|A7F31C|1.3.2"
 *
 * On connect the device sends one identity line:
 *   ID model=PI-1 serial=A7F31C fw=1.3.2
 * Then 10..20 times per second a telemetry line:
 *   T5 B79 U7.63 C0 H0 E18 S190 V36
 *     T target step   B charge %   U volts   C charging
 *     H heater duty   E temperature C        S,V current setpoints
 *
 * Commands to the device:
 *   S190          set a setpoint (key comes from the model descriptor)
 *   ID?           resend identity
 *   OTA <size>    begin firmware transfer, then raw chunks into RX
 *   OTA!          finish and switch slots
 * Device replies:
 *   OTAOK <bytes accepted>    OTAERR <reason>    OTADONE
 */
object Proto {
    val SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /*
     * Сервис обновления прошивки. Его поднимает библиотека BLEOTA на приборе,
     * UUID у неё свои и менять их нельзя — по ним же прибор находит и
     * веб-обновлятор в Chrome (https://gb88.github.io/BLEOTA/).
     */
    val OTA_SERVICE: UUID = UUID.fromString("00008018-0000-1000-8000-00805f9b34fb")
    val OTA_RECV: UUID = UUID.fromString("00008020-0000-1000-8000-00805f9b34fb")
    val OTA_CMD: UUID = UUID.fromString("00008022-0000-1000-8000-00805f9b34fb")
}

/** One adjustable setpoint of a model. */
data class Control(
    val key: String,
    val name: String,
    val min: Int,
    val max: Int,
)

/** What a model looks like on screen. Add a model here and in its firmware. */
data class Model(
    val id: String,
    val title: String,
    val subtitle: String,
    val levels: Int,
    val controls: List<Control>,
)

object Models {
    private val known = listOf(
        Model(
            id = "PI-1", title = "PI-1", subtitle = "Импульсный, моно-катушка", levels = 8,
            controls = listOf(
                Control("S", "Чувствительность", 0, 255),
                Control("V", "Громкость", 0, 60),
            )
        ),
        Model(
            id = "PI-2", title = "PI-2", subtitle = "Импульсный, DD-катушка", levels = 8,
            controls = listOf(
                Control("S", "Чувствительность", 0, 255),
                Control("V", "Громкость", 0, 60),
                Control("G", "Баланс грунта", 0, 100),
            )
        ),
    ).associateBy { it.id }

    /** Unknown models stay usable: generic setpoints instead of a refusal. */
    fun of(id: String?): Model = known[id] ?: Model(
        id = id ?: "?", title = id ?: "Неизвестная модель",
        subtitle = "паспорт не разобран", levels = 8,
        controls = listOf(Control("S", "Уставка 1", 0, 255), Control("V", "Уставка 2", 0, 60))
    )
}

/** Everything the device reports. */
data class Telemetry(
    val level: Int = 0,
    val batteryPct: Int = 0,
    val volts: Double = 0.0,
    val charging: Boolean = false,
    val heaterDuty: Int = 0,
    val tempC: Int = 0,
    val setpoints: Map<String, Int> = emptyMap(),
)

data class Identity(val model: String, val serial: String, val fw: String)

object Parser {
    private val idRe = Regex("""(\w+)=([\w.\-]+)""")

    fun identity(line: String): Identity? {
        if (!line.startsWith("ID ")) return null
        val m = idRe.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
        return Identity(m["model"] ?: return null, m["serial"] ?: "-", m["fw"] ?: "-")
    }

    /** Merges a telemetry line into the previous state: fields may be omitted. */
    fun telemetry(line: String, prev: Telemetry, keys: List<String>): Telemetry {
        fun num(k: String): Double? =
            Regex("""(?:^| )$k(-?[\d.]+)""").find(line)?.groupValues?.get(1)?.toDoubleOrNull()

        val sp = prev.setpoints.toMutableMap()
        keys.forEach { k -> num(k)?.let { sp[k] = it.toInt() } }

        return prev.copy(
            level = num("T")?.toInt() ?: prev.level,
            batteryPct = num("B")?.toInt() ?: prev.batteryPct,
            volts = num("U") ?: prev.volts,
            charging = num("C")?.let { it >= 1 } ?: prev.charging,
            heaterDuty = num("H")?.toInt() ?: prev.heaterDuty,
            tempC = num("E")?.toInt() ?: prev.tempC,
            setpoints = sp,
        )
    }
}
