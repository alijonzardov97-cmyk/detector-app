package com.alijon.detector

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/* ===========================================================================
 *  Пользовательские настройки приложения
 *
 *  Всё, что человек крутит для себя: громкости, режим звука, яркость, порог
 *  флажка. Значения — состояние Compose, поэтому экраны и звуковой поток
 *  видят изменения сразу; в SharedPreferences они уходят тем же движением,
 *  чтобы пережить перезапуск.
 *
 *  Настройки ПРИБОРА (делитель, ПИД) сюда не попадают: они живут в самом
 *  приборе и приходят оттуда, телефон их только показывает.
 * ===========================================================================
 */

/** Как звучит прибор в телефоне. */
enum class SoundMode {
    /** Обычный тон: фон около 240 Гц, цель уводит частоту вверх. */
    NORMAL,

    /**
     * Для тех, кто плохо слышит высокие.
     *
     * Фоном идёт коричневый шум — он ощущается всем ухом, а не одной узкой
     * полосой, и не теряется первым при возрастной потере слуха. Сигналом
     * служит тон, который ползёт от 150 к 350 Гц: это низкий регистр, где
     * разборчивость сохраняется дольше всего.
     */
    HEARING,
}

class Prefs(ctx: Context) {

    private val p = ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    // --- звук ---
    var soundMode by mutableStateOf(
        runCatching { SoundMode.valueOf(p.getString("soundMode", "NORMAL")!!) }
            .getOrDefault(SoundMode.NORMAL)
    )
        private set

    /** Громкость фонового гула или шума, 0..1. */
    var bgVolume by mutableFloatStateOf(p.getFloat("bgVolume", 0.35f))
        private set

    /** Громкость сигнала цели, 0..1. */
    var sigVolume by mutableFloatStateOf(p.getFloat("sigVolume", 0.85f))
        private set

    // --- экран ---
    var autoBrightness by mutableStateOf(p.getBoolean("autoBright", false))
        private set

    /** Ручная яркость, 0..1. Действует, когда автоматика выключена. */
    var brightness by mutableFloatStateOf(p.getFloat("bright", 0.7f))
        private set

    // --- находки ---
    /**
     * С какого деления шкалы считать, что найден металл.
     *
     * Это же значение включает GPS и ставит флажок: раньше порог был зашит
     * числом 4, а грунт и катушки у всех разные.
     */
    var flagLevel by mutableIntStateOf(p.getInt("flagLevel", AUTO_FLAG_LEVEL))
        private set

    // --- фоновая работа ---
    var background by mutableStateOf(p.getBoolean("background", false))
        private set

    fun setSoundMode(v: SoundMode) { soundMode = v; p.edit().putString("soundMode", v.name).apply() }
    fun setBgVolume(v: Float) { bgVolume = v.coerceIn(0f, 1f); p.edit().putFloat("bgVolume", bgVolume).apply() }
    fun setSigVolume(v: Float) { sigVolume = v.coerceIn(0f, 1f); p.edit().putFloat("sigVolume", sigVolume).apply() }
    fun setAutoBrightness(v: Boolean) { autoBrightness = v; p.edit().putBoolean("autoBright", v).apply() }
    fun setBrightness(v: Float) { brightness = v.coerceIn(0.02f, 1f); p.edit().putFloat("bright", brightness).apply() }
    fun setFlagLevel(v: Int) { flagLevel = v.coerceIn(1, 8); p.edit().putInt("flagLevel", flagLevel).apply() }
    fun setBackground(v: Boolean) { background = v; p.edit().putBoolean("background", v).apply() }
}
