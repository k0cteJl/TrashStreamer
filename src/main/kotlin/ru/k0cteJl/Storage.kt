package ru.k0cteJl

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Level
import java.util.logging.Logger

/** JSON-файл с атомарной записью (tmp + move), чтобы падение посреди записи не портило данные. */
class JsonFileStore<T>(
    private val path: Path,
    private val serializer: KSerializer<T>,
    private val default: () -> T,
) {
    private val log = Logger.getLogger("Storage")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): T {
        if (!Files.exists(path)) return default()
        return try {
            json.decodeFromString(serializer, Files.readString(path))
        } catch (e: Exception) {
            val aside = path.resolveSibling("${path.fileName}.corrupt-${System.currentTimeMillis()}")
            log.log(Level.SEVERE, "Файл $path повреждён, откладываю в $aside и начинаю с пустого (бэкапы лежат в backups/)", e)
            runCatching { Files.move(path, aside) }
            default()
        }
    }

    fun save(value: T) {
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(serializer, value))
        Files.move(tmp, path, ATOMIC_MOVE)
    }
}

@Serializable
data class UserState(
    val selectedGroup: String? = null,
    val subscriptions: Set<String> = emptySet(),
)

class UserStore(dir: Path) {
    private val log = Logger.getLogger("UserStore")
    private val store = JsonFileStore(
        dir.resolve("users.json"),
        MapSerializer(Long.serializer(), UserState.serializer()),
    ) { emptyMap() }
    private val users: MutableMap<Long, UserState> = store.load().toMutableMap()

    init {
        log.info("Загружено пользователей: ${users.size}")
    }

    @Synchronized
    fun get(chatId: Long): UserState = users[chatId] ?: UserState()

    @Synchronized
    fun update(chatId: Long, change: (UserState) -> UserState): UserState {
        val updated = change(get(chatId))
        users[chatId] = updated
        persist()
        return updated
    }

    @Synchronized
    fun remove(chatId: Long) {
        if (users.remove(chatId) != null) persist()
    }

    @Synchronized
    fun subscribersOf(group: String): List<Long> =
        users.filterValues { group in it.subscriptions }.keys.toList()

    private fun persist() {
        try {
            store.save(users.toMap())
        } catch (e: Exception) {
            log.log(Level.SEVERE, "Не удалось сохранить users.json", e)
        }
    }
}

/** Последние известные расписания: группа -> дата -> пары. Нужны, чтобы замечать изменения. */
class SnapshotStore(dir: Path) {
    private val log = Logger.getLogger("SnapshotStore")
    private val store = JsonFileStore(
        dir.resolve("snapshots.json"),
        MapSerializer(String.serializer(), MapSerializer(String.serializer(), ListSerializer(Lesson.serializer()))),
    ) { emptyMap() }
    private val data: MutableMap<String, MutableMap<String, List<Lesson>>> =
        store.load().mapValuesTo(mutableMapOf()) { it.value.toMutableMap() }

    @Synchronized
    fun get(group: String, date: String): List<Lesson>? = data[group]?.get(date)

    @Synchronized
    fun put(group: String, date: String, lessons: List<Lesson>) {
        val days = data.getOrPut(group) { mutableMapOf() }
        days[date] = lessons
        days.keys.sortedDescending().drop(3).forEach { days.remove(it) }
        try {
            store.save(data.mapValues { it.value.toMap() })
        } catch (e: Exception) {
            log.log(Level.SEVERE, "Не удалось сохранить snapshots.json", e)
        }
    }
}

class BackupService(private val dataDir: Path, private val keep: Int) {
    private val log = Logger.getLogger("Backup")
    private val root = dataDir.resolve("backups")
    private val stampFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    fun run() {
        val target = root.resolve(LocalDateTime.now().format(stampFmt))
        Files.createDirectories(target)
        var copied = 0
        Files.list(dataDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .forEach {
                    Files.copy(it, target.resolve(it.fileName), StandardCopyOption.REPLACE_EXISTING)
                    copied++
                }
        }
        val old = Files.list(root).use { s -> s.filter { Files.isDirectory(it) }.sorted().toList() }.dropLast(keep)
        old.forEach { it.toFile().deleteRecursively() }
        log.info("Бэкап $target: файлов $copied, удалено старых: ${old.size}")
    }
}
