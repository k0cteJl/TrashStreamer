package ru.k0cteJl

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

private const val BASE_API_URL = "https://msapi.top-academy.ru/api/v2"
private const val AUTH_URL = "$BASE_API_URL/auth/login"
private const val SCHEDULE_URL = "$BASE_API_URL/schedule/operations/get-by-date?date_filter="

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/128.0.0.0 YaBrowser/24.10.0.0 Safari/537.36"
private const val ORIGIN = "https://journal.top-academy.ru"
private const val REFERER = "https://journal.top-academy.ru/"

/** status == null — сетевая ошибка/таймаут/кривой ответ. */
class ApiException(message: String, val status: Int? = null, cause: Throwable? = null) : Exception(message, cause) {
    val retryable: Boolean get() = status == null || status >= 500 || status == 429
}

@Serializable
private data class AuthResponse(val access_token: String)

class TopAcademyClient {
    private val log = Logger.getLogger("TopAcademyClient")
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Токены кэшируются по группам; при 401 логинимся заново. */
    private val tokens = ConcurrentHashMap<String, String>()

    fun fetchSchedule(group: GroupConfig, date: LocalDate): List<Lesson> =
        withRetry("расписание ${group.groupName} на $date") {
            val token = tokens[group.groupName] ?: authorize(group)
            try {
                requestSchedule(token, date)
            } catch (e: ApiException) {
                if (e.status != 401) throw e
                log.info("Токен группы ${group.groupName} недействителен, авторизуюсь заново")
                tokens.remove(group.groupName)
                requestSchedule(authorize(group), date)
            }
        }

    private fun authorize(group: GroupConfig): String {
        val body = buildJsonObject {
            put("username", group.username)
            put("password", group.password)
            put("application_key", group.applicationKey)
        }
        val request = baseRequest(AUTH_URL)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val text = send(request, "авторизация ${group.groupName}")
        val token = try {
            json.decodeFromString<AuthResponse>(text).access_token
        } catch (e: Exception) {
            throw ApiException("авторизация ${group.groupName}: неожиданный ответ сервера", cause = e)
        }
        tokens[group.groupName] = token
        log.info("Авторизация группы ${group.groupName} успешна")
        return token
    }

    private fun requestSchedule(token: String, date: LocalDate): List<Lesson> {
        val request = baseRequest("$SCHEDULE_URL$date")
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        return parseLessons(send(request, "запрос расписания"))
    }

    private fun parseLessons(body: String): List<Lesson> {
        val array = try {
            json.parseToJsonElement(body).jsonArray
        } catch (e: Exception) {
            throw ApiException("неожиданный формат расписания: ${body.take(200)}", cause = e)
        }
        return array.mapNotNull { (it as? JsonObject)?.toLesson() }
            .sortedWith(compareBy({ it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.start }))
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.toLesson() = Lesson(
        number = str("lesson"),
        start = str("started_at"),
        end = str("finished_at"),
        subject = str("subject_name"),
        teacher = str("teacher_name"),
        room = str("room_name"),
    )

    private fun baseRequest(url: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Origin", ORIGIN)
            .header("Referer", REFERER)

    private fun send(request: HttpRequest, what: String): String {
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw ApiException("$what: сетевая ошибка (${e.javaClass.simpleName}: ${e.message})", cause = e)
        }
        if (response.statusCode() !in 200..299) {
            throw ApiException("$what: HTTP ${response.statusCode()} ${response.body().take(300)}", response.statusCode())
        }
        return response.body()
    }

    private fun <T> withRetry(what: String, attempts: Int = 3, block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: ApiException) {
                if (!e.retryable || attempt >= attempts) throw e
                log.warning("$what: попытка $attempt/$attempts не удалась (${e.message}), повторяю")
                Thread.sleep(2000L * attempt)
                attempt++
            }
        }
    }
}
