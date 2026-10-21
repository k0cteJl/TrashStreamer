package ru.k0cteJl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class GroupConfig(
    @SerialName("group_name") val groupName: String,
    val username: String,
    val password: String,
    @SerialName("application_key") val applicationKey: String,
)

@Serializable
data class AppConfig(
    @SerialName("bot_token") val botToken: String = "",
    @SerialName("admin_chat_id") val adminChatId: Long? = null,
    @SerialName("check_interval_minutes") val checkIntervalMinutes: Long = 60,
    @SerialName("backup_interval_minutes") val backupIntervalMinutes: Long = 60,
    @SerialName("backup_keep") val backupKeep: Int = 168,
    val timezone: String = "Europe/Moscow",
    @SerialName("data_dir") val dataDir: String = "data",
    val groups: List<GroupConfig>,
)

object ConfigLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(path: Path): AppConfig {
        require(Files.exists(path)) { "Конфиг не найден: ${path.toAbsolutePath()}" }
        var config = json.decodeFromString<AppConfig>(Files.readString(path))

        System.getenv("BOT_TOKEN")?.takeIf { it.isNotBlank() }?.let { config = config.copy(botToken = it) }

        require(config.botToken.isNotBlank()) { "Не задан bot_token (в конфиге или в переменной BOT_TOKEN)" }
        require(config.groups.isNotEmpty()) { "В конфиге нет ни одной группы" }
        require(config.groups.map { it.groupName }.toSet().size == config.groups.size) { "group_name должны быть уникальными" }
        config.groups.forEach {
            require(("pick:" + it.groupName).toByteArray().size <= 64) { "Слишком длинное имя группы: ${it.groupName}" }
            require(it.username.isNotBlank() && it.password.isNotBlank() && it.applicationKey.isNotBlank()) {
                "Не заполнены данные для входа у группы ${it.groupName}"
            }
        }
        require(config.checkIntervalMinutes > 0 && config.backupIntervalMinutes > 0) { "Интервалы должны быть > 0" }
        java.time.ZoneId.of(config.timezone)
        return config
    }
}
