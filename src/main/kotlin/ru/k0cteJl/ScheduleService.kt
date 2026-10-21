package ru.k0cteJl

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

@Serializable
data class Lesson(
    val number: String,
    val start: String,
    val end: String,
    val subject: String,
    val teacher: String,
    val room: String,
)

/** Кэш поверх API: 100 человек, жмущих кнопки, не должны долбить журнал. */
class ScheduleService(private val client: TopAcademyClient) {
    private class Cached(val at: Long, val lessons: List<Lesson>)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** Бросает ApiException, если журнал недоступен. */
    fun get(group: GroupConfig, date: LocalDate, maxAgeMs: Long = 60_000): List<Lesson> {
        val key = "${group.groupName}|$date"
        fun fresh() = cache[key]?.takeIf { System.currentTimeMillis() - it.at < maxAgeMs }?.lessons

        fresh()?.let { return it }
        synchronized(locks.computeIfAbsent(group.groupName) { Any() }) {
            fresh()?.let { return it }
            val lessons = client.fetchSchedule(group, date)
            val now = System.currentTimeMillis()
            cache[key] = Cached(now, lessons)
            cache.entries.removeIf { now - it.value.at > 3_600_000 }
            return lessons
        }
    }
}

/** Раз в N минут сравнивает расписание на сегодня с прошлым и рассылает подписчикам изменения. */
class ScheduleWatcher(
    private val config: AppConfig,
    private val service: ScheduleService,
    private val snapshots: SnapshotStore,
    private val users: UserStore,
    private val send: (chatId: Long, html: String) -> Unit,
    private val alert: (String) -> Unit,
) {
    private val log = Logger.getLogger("Watcher")
    private val zone = ZoneId.of(config.timezone)

    private val failures = mutableMapOf<String, Int>()

    fun checkAll() {
        log.info("Проверка расписания, групп: ${config.groups.size}")
        for (group in config.groups) {
            try {
                checkGroup(group)
            } catch (e: Exception) {
                log.log(Level.SEVERE, "Сбой при проверке группы ${group.groupName}", e)
            }
        }
    }

    private fun checkGroup(group: GroupConfig) {
        val today = LocalDate.now(zone)
        val fresh = try {
            service.get(group, today, maxAgeMs = 0)
        } catch (e: Exception) {
            onFailure(group, e)
            return
        }
        onSuccess(group)

        val key = today.toString()
        val old = snapshots.get(group.groupName, key)
        if (old == fresh) {
            log.info("${group.groupName} $today: без изменений (пар: ${fresh.size})")
            return
        }
        snapshots.put(group.groupName, key, fresh)

        if (old == null) {
            log.info("${group.groupName} $today: первый снимок (пар: ${fresh.size}), без уведомления")
            return
        }

        val recipients = users.subscribersOf(group.groupName)
        log.info("${group.groupName} $today: РАСПИСАНИЕ ИЗМЕНИЛОСЬ, получателей: ${recipients.size}")
        val text = Fmt.changeNotice(group.groupName, today, old, fresh)
        for (chatId in recipients) {
            try {
                send(chatId, text)
            } catch (e: Exception) {
                log.log(Level.WARNING, "Не удалось отправить уведомление chat=$chatId", e)
            }
            Thread.sleep(50)
        }
    }

    private fun onFailure(group: GroupConfig, e: Exception) {
        val n = (failures[group.groupName] ?: 0) + 1
        failures[group.groupName] = n
        log.log(Level.SEVERE, "Не удалось получить расписание ${group.groupName} (подряд неудач: $n)", e)
        if (n == 2) alert("⚠️ Журнал не отвечает для группы ${group.groupName} уже $n проверки подряд.\n${e.message}")
    }

    private fun onSuccess(group: GroupConfig) {
        val before = failures.remove(group.groupName) ?: 0
        if (before >= 2) alert("✅ Группа ${group.groupName}: связь с журналом восстановлена.")
    }
}
