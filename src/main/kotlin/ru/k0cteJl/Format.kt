package ru.k0cteJl

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Всё оформление сообщений (Telegram HTML) в одном месте. */
object Fmt {
    private val ru = Locale.forLanguageTag("ru")
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d MMMM", ru)
    private val shortFmt = DateTimeFormatter.ofPattern("dd.MM", ru)

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun time(s: String) = if (s.length >= 5 && s[2] == ':') s.substring(0, 5) else s

    private fun relative(date: LocalDate, today: LocalDate): String? = when (date) {
        today -> "Сегодня"
        today.plusDays(1) -> "Завтра"
        today.minusDays(1) -> "Вчера"
        else -> null
    }

    fun shortLabel(date: LocalDate, today: LocalDate): String = relative(date, today) ?: date.format(shortFmt)

    private fun dayTitle(date: LocalDate, today: LocalDate): String {
        val day = date.format(dayFmt).replaceFirstChar { it.uppercase() }
        val rel = relative(date, today)
        return if (rel != null) "$rel · $day" else day
    }

    private fun lessonBlock(l: Lesson): String = buildString {
        append("<b>${esc(l.number)} пара</b> · ${time(l.start)}–${time(l.end)}\n")
        append("📚 ${esc(l.subject.ifBlank { "—" })}\n")
        if (l.teacher.isNotBlank()) append("👤 ${esc(l.teacher)}\n")
        if (l.room.isNotBlank()) append("🚪 ${esc(l.room)}\n")
    }.trimEnd()

    private fun lessonsText(lessons: List<Lesson>): String =
        if (lessons.isEmpty()) "😴 В этот день пар нет."
        else lessons.joinToString("\n\n") { lessonBlock(it) } + "\n\n<i>ℹ️ Кабинеты могут быть неточными</i>"

    fun schedule(group: String, date: LocalDate, today: LocalDate, lessons: List<Lesson>): String =
        "📅 <b>${dayTitle(date, today)}</b>\n👥 ${esc(group)}\n\n" + lessonsText(lessons)

    private fun change(icon: String, old: String, new: String): String? =
        if (old == new) null else "$icon ${esc(old.ifBlank { "—" })} → ${esc(new.ifBlank { "—" })}"

    private fun diffLines(old: List<Lesson>, new: List<Lesson>): List<String> {
        val o = old.associateBy { it.number }
        val n = new.associateBy { it.number }
        val out = mutableListOf<String>()
        (o.keys + n.keys).sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.forEach { key ->
            val a = o[key]
            val b = n[key]
            when {
                a == null && b != null ->
                    out += "➕ <b>Добавлена ${esc(key)} пара</b>: ${esc(b.subject)} (${time(b.start)}–${time(b.end)})"
                a != null && b == null ->
                    out += "➖ <b>Отменена ${esc(key)} пара</b>: ${esc(a.subject)}"
                a != null && b != null && a != b -> {
                    val parts = listOfNotNull(
                        change("📚", a.subject, b.subject),
                        change("👤", a.teacher, b.teacher),
                        change("🚪", a.room, b.room),
                        if (a.start != b.start || a.end != b.end)
                            "🕒 ${time(a.start)}–${time(a.end)} → ${time(b.start)}–${time(b.end)}" else null,
                    )
                    out += "✏️ <b>Изменена ${esc(key)} пара</b>\n" + parts.joinToString("\n")
                }
            }
        }
        return out
    }

    fun changeNotice(group: String, date: LocalDate, old: List<Lesson>, new: List<Lesson>): String {
        val diff = diffLines(old, new).ifEmpty { listOf("✏️ Расписание обновлено") }
        val head = "🔔 <b>Расписание изменилось</b>\n👥 ${esc(group)} · ${dayTitle(date, date)}\n\n" +
                diff.joinToString("\n\n")
        val full = head + "\n\n━━━━━━━━━━\n<b>Актуальное расписание:</b>\n\n" + lessonsText(new)
        return if (full.length <= 4000) full else head.take(4000)
    }
}
