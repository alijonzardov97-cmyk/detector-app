package com.alijon.detector

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/* ===========================================================================
 *  Оформление приложения
 *
 *  Цвета объявлены как состояние Compose, а не как константы. Благодаря этому
 *  смена темы не требует ни перезапуска, ни переписывания экранов: любое место,
 *  которое читает Ground или Brass, автоматически подписано на изменение и
 *  перерисуется само. Именно поэтому здесь `var ... by mutableStateOf`, а не
 *  привычный `val`.
 * ===========================================================================
 */

internal var Ground by mutableStateOf(Color(0xFF14100D))
internal var Panel by mutableStateOf(Color(0xFF1E1813))
internal var Edge by mutableStateOf(Color(0xFF3A2F24))
internal var EdgeSoft by mutableStateOf(Color(0xFF2C241C))
internal var Ink by mutableStateOf(Color(0xFFECE3D4))
internal var InkDim by mutableStateOf(Color(0xFFA1907A))
internal var InkFaint by mutableStateOf(Color(0xFF6D6053))
internal var Brass by mutableStateOf(Color(0xFFE8A33D))
internal var Oxide by mutableStateOf(Color(0xFFF0552A))
internal var Slate by mutableStateOf(Color(0xFF7D9BB0))
internal var Ok by mutableStateOf(Color(0xFF84B06A))
internal var Crit by mutableStateOf(Color(0xFFD8482E))

/** Готовый набор цветов. Добавить свой — дописать строку в Themes.all. */
data class Theme(
    val id: String,
    val name: String,
    val ground: Long,
    val panel: Long,
    val edge: Long,
    val edgeSoft: Long,
    val ink: Long,
    val inkDim: Long,
    val inkFaint: Long,
    val accent: Long,
    val warn: Long,
    val cool: Long,
    val good: Long,
    val bad: Long,
)

object Themes {
    val all = listOf(
        Theme(
            "coyote", "Койот",
            0xFF14100D, 0xFF1E1813, 0xFF3A2F24, 0xFF2C241C,
            0xFFECE3D4, 0xFFA1907A, 0xFF6D6053,
            0xFFE8A33D, 0xFFF0552A, 0xFF7D9BB0, 0xFF84B06A, 0xFFD8482E,
        ),
        Theme(
            "night", "Ночь",
            0xFF0B0B0C, 0xFF161719, 0xFF31343A, 0xFF232529,
            0xFFE6E8EC, 0xFF9AA0A8, 0xFF666C74,
            0xFFE8A33D, 0xFFF0552A, 0xFF7D9BB0, 0xFF84B06A, 0xFFD8482E,
        ),
        Theme(
            "olive", "Олива",
            0xFF10130E, 0xFF1B2018, 0xFF394130, 0xFF272E22,
            0xFFE4E9DC, 0xFF9BA890, 0xFF6A7562,
            0xFFB7C46A, 0xFFE0733A, 0xFF8FAF9B, 0xFF9CC46A, 0xFFCF5140,
        ),
        Theme(
            "steel", "Сталь",
            0xFF0D1116, 0xFF161C24, 0xFF2E3A47, 0xFF212932,
            0xFFDFE7EF, 0xFF93A3B4, 0xFF60707F,
            0xFF63B3E8, 0xFFE8863D, 0xFF8FB6D6, 0xFF6FC08A, 0xFFDC5A4A,
        ),
        Theme(
            "sand", "Песок",
            0xFFF2ECE1, 0xFFFFFFFF, 0xFFCDBFA8, 0xFFE0D6C6,
            0xFF2A241C, 0xFF6B5F4E, 0xFF8E8271,
            0xFFB07A1E, 0xFFC2411D, 0xFF3F6B8A, 0xFF4C7A3A, 0xFFB33322,
        ),
    )

    fun byId(id: String?): Theme = all.firstOrNull { it.id == id } ?: all.first()
}

/**
 * Выбранное оформление. Хранится в настройках приложения, поэтому переживает
 * перезапуск.
 */
class Appearance(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    var currentId: String = prefs.getString("theme", Themes.all.first().id) ?: Themes.all.first().id
        private set

    init { applyTheme(currentId, save = false) }

    fun applyTheme(id: String, save: Boolean = true) {
        val t = Themes.byId(id)
        currentId = t.id
        Ground = Color(t.ground)
        Panel = Color(t.panel)
        Edge = Color(t.edge)
        EdgeSoft = Color(t.edgeSoft)
        Ink = Color(t.ink)
        InkDim = Color(t.inkDim)
        InkFaint = Color(t.inkFaint)
        Brass = Color(t.accent)
        Oxide = Color(t.warn)
        Slate = Color(t.cool)
        Ok = Color(t.good)
        Crit = Color(t.bad)
        if (save) prefs.edit().putString("theme", t.id).apply()
    }
}
