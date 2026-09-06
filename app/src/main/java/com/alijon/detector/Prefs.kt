package com.alijon.detector

import android.content.Context
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf

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

/*
 * ПОЧЕМУ ЗДЕСЬ ЯВНЫЕ get/set, А НЕ `by mutableStateOf` С `private set`.
 *
 * Прошлый вариант выглядел короче:
 *
 *      var bgVolume by mutableFloatStateOf(...)
 *          private set
 *      fun setBgVolume(v: Float) { ... }
 *
 * и не собирался вовсе. Свойство `bgVolume`, даже с приватным сеттером,
 * порождает в байт-коде метод `setBgVolume(float)`. Ровно такое же имя и
 * такую же сигнатуру получает и обычная функция `setBgVolume`. Компилятор
 * Kotlin на это отвечает «Platform declaration clash: … имеют одинаковую
 * сигнатуру JVM», и так по одной ошибке на каждую из семи настроек.
 *
 * Свои get/set решают это по существу, а не обходят: сохранение переезжает
 * прямо в сеттер свойства, отдельные функции становятся не нужны, и записать
 * настройку мимо SharedPreferences уже нельзя — что бы ни присвоили, оно
 * тем же движением ляжет на диск.
 */
class Prefs(ctx: Context) {

    private val p = ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    // --- звук ---
    private val soundModeState = mutableStateOf(
        runCatching { SoundMode.valueOf(p.getString("soundMode", "NORMAL")!!) }
            .getOrDefault(SoundMode.NORMAL)
    )
    var soundMode: SoundMode
        get() = soundModeState.value
        set(v) {
            soundModeState.value = v
            p.edit().putString("soundMode", v.name).apply()
        }

    /** Громкость фонового гула или шума, 0..1. */
    private val bgVolumeState = mutableFloatStateOf(p.getFloat("bgVolume", 0.35f))
    var bgVolume: Float
        get() = bgVolumeState.floatValue
        set(v) {
            bgVolumeState.floatValue = v.coerceIn(0f, 1f)
            p.edit().putFloat("bgVolume", bgVolumeState.floatValue).apply()
        }

    /** Громкость сигнала цели, 0..1. */
    private val sigVolumeState = mutableFloatStateOf(p.getFloat("sigVolume", 0.85f))
    var sigVolume: Float
        get() = sigVolumeState.floatValue
        set(v) {
            sigVolumeState.floatValue = v.coerceIn(0f, 1f)
            p.edit().putFloat("sigVolume", sigVolumeState.floatValue).apply()
        }

    // --- экран ---
    private val autoBrightnessState = mutableStateOf(p.getBoolean("autoBright", false))
    var autoBrightness: Boolean
        get() = autoBrightnessState.value
        set(v) {
            autoBrightnessState.value = v
            p.edit().putBoolean("autoBright", v).apply()
        }

    /** Ручная яркость, 0..1. Действует, когда автоматика выключена. */
    private val brightnessState = mutableFloatStateOf(p.getFloat("bright", 0.7f))
    var brightness: Float
        get() = brightnessState.floatValue
        set(v) {
            // Нижняя граница не 0: на нуле часть прошивок гасит подсветку
            // совсем, и экран не оживает даже от касания.
            brightnessState.floatValue = v.coerceIn(0.02f, 1f)
            p.edit().putFloat("bright", brightnessState.floatValue).apply()
        }

    // --- находки ---
    /**
     * С какого деления шкалы считать, что найден металл.
     *
     * Это же значение включает GPS и ставит флажок: раньше порог был зашит
     * числом 4, а грунт и катушки у всех разные.
     */
    private val flagLevelState = mutableIntStateOf(p.getInt("flagLevel", AUTO_FLAG_LEVEL))
    var flagLevel: Int
        get() = flagLevelState.intValue
        set(v) {
            flagLevelState.intValue = v.coerceIn(1, 8)
            p.edit().putInt("flagLevel", flagLevelState.intValue).apply()
        }

    // --- фоновая работа ---
    private val backgroundState = mutableStateOf(p.getBoolean("background", false))
    var background: Boolean
        get() = backgroundState.value
        set(v) {
            backgroundState.value = v
            p.edit().putBoolean("background", v).apply()
        }
}
