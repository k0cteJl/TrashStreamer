package ru.k0cteJl

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.dispatcher.telegramError
import com.github.kotlintelegrambot.entities.BotCommand
import com.github.kotlintelegrambot.entities.CallbackQuery
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.types.TelegramBotResult
import java.time.LocalDate
import java.time.ZoneId
import java.util.logging.Level
import java.util.logging.Logger

class BotUi(
    private val config: AppConfig,
    private val service: ScheduleService,
    private val users: UserStore,
) {
    private val log = Logger.getLogger("BotUi")
    private val groups = config.groups.associateBy { it.groupName }
    private val zone = ZoneId.of(config.timezone)
    private lateinit var tg: Bot

    private data class Screen(val text: String, val markup: InlineKeyboardMarkup)

    fun start() {
        tg = bot {
            token = config.botToken
            dispatch {
                command("start") { safely(message.chat.id) { sendScreen(message.chat.id, welcome()) } }
                command("menu") { safely(message.chat.id) { sendScreen(message.chat.id, welcome()) } }
                command("get") {
                    val chatId = message.chat.id
                    safely(chatId) {
                        val date = args.firstOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today()
                        sendScreen(chatId, scheduleScreen(chatId, date))
                    }
                }
                command("group") { safely(message.chat.id) { sendScreen(message.chat.id, groupScreen(message.chat.id)) } }
                command("subscribe") { safely(message.chat.id) { sendScreen(message.chat.id, subsScreen(message.chat.id)) } }
                command("unsubscribe") {
                    val chatId = message.chat.id
                    safely(chatId) {
                        users.update(chatId) { it.copy(subscriptions = emptySet()) }
                        send(chatId, "🔕 Вы отписались от всех уведомлений.", menuMarkup())
                    }
                }
                callbackQuery {
                    safely(callbackQuery.message?.chat?.id) { onCallback(callbackQuery) }
                }
                text {
                    if (!text.startsWith("/")) {
                        safely(message.chat.id) { send(message.chat.id, "Не совсем понял 🤔 Вот что я умею:", menuMarkup()) }
                    }
                }
                telegramError { log.severe("Ошибка Telegram API: ${error.getErrorMessage()}") }
            }
        }
        tg.setMyCommands(
            listOf(
                BotCommand("get", "Расписание на сегодня"),
                BotCommand("menu", "Меню"),
                BotCommand("group", "Выбрать группу"),
                BotCommand("subscribe", "Уведомления об изменениях"),
                BotCommand("unsubscribe", "Отписаться от всего"),
            )
        )
        tg.startPolling()
        log.info("Бот запущен, групп: ${groups.size}")
    }

    fun stop() {
        if (this::tg.isInitialized) tg.stopPolling()
    }

    fun send(chatId: Long, html: String, markup: InlineKeyboardMarkup? = null) {
        val result = tg.sendMessage(
            chatId = ChatId.fromId(chatId),
            text = html,
            parseMode = ParseMode.HTML,
            replyMarkup = markup,
        )
        handleResult(chatId, "sendMessage", result)
    }

    private fun sendScreen(chatId: Long, screen: Screen) = send(chatId, screen.text, screen.markup)

    private fun editScreen(chatId: Long, messageId: Long, screen: Screen) {
        val result = tg.editMessageText(
            chatId = ChatId.fromId(chatId),
            messageId = messageId,
            text = screen.text,
            parseMode = ParseMode.HTML,
            replyMarkup = screen.markup,
        )
        handleEditResult(chatId, result)
    }

    private fun handleEditResult(chatId: Long, result: Pair<retrofit2.Response<*>?, Exception?>) {
        val (response, exception) = result
        when {
            exception != null ->
                log.log(Level.WARNING, "editMessageText chat=$chatId: сетевая ошибка", exception)

            response != null && !response.isSuccessful -> {
                val body = response.errorBody()?.string().orEmpty()
                when {
                    response.code() == 403 -> {
                        log.info("chat=$chatId заблокировал бота, удаляю подписчика")
                        users.remove(chatId)
                    }
                    "not modified" in body -> Unit
                    else -> log.warning("editMessageText chat=$chatId: HTTP ${response.code()} $body")
                }
            }
        }
    }

    fun alertAdmin(text: String) {
        val admin = config.adminChatId ?: return
        runCatching { send(admin, Fmt.esc(text)) }
    }

    /** Если вдруг имена в TelegramBotResult отличаются в вашей версии — правится только здесь. */
    private fun handleResult(chatId: Long, op: String, result: TelegramBotResult<Message>) {
        if (result !is TelegramBotResult.Error) return
        when {
            result is TelegramBotResult.Error.TelegramApi && result.errorCode == 403 -> {
                log.info("chat=$chatId заблокировал бота, удаляю подписчика")
                users.remove(chatId)
            }
            result.toString().contains("not modified") -> Unit
            else -> log.warning("$op chat=$chatId: $result")
        }
    }

    private fun safely(chatId: Long?, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.log(Level.SEVERE, "Ошибка в обработчике (chat=$chatId)", e)
            if (chatId != null) runCatching { send(chatId, "⚠️ Что-то пошло не так. Попробуйте ещё раз чуть позже.") }
        }
    }

    private fun onCallback(cb: CallbackQuery) {
        val msg = cb.message ?: return
        val chatId = msg.chat.id
        tg.answerCallbackQuery(cb.id)

        val data = cb.data
        val screen = when {
            data.startsWith("d:") -> scheduleScreen(chatId, LocalDate.parse(data.removePrefix("d:")))
            data == "menu" -> welcome()
            data == "grp" -> groupScreen(chatId)
            data.startsWith("pick:") -> {
                val name = data.removePrefix("pick:")
                if (name in groups) users.update(chatId) { it.copy(selectedGroup = name) }
                scheduleScreen(chatId, today())
            }
            data == "subs" -> subsScreen(chatId)
            data.startsWith("tog:") -> {
                val name = data.removePrefix("tog:")
                if (name in groups) toggleSubscription(chatId, name)
                subsScreen(chatId)
            }
            else -> {
                log.warning("Неизвестный callback: $data (chat=$chatId)")
                return
            }
        }
        editScreen(chatId, msg.messageId, screen)
    }

    private fun toggleSubscription(chatId: Long, group: String) {
        users.update(chatId) { st ->
            val subs = if (group in st.subscriptions) st.subscriptions - group else st.subscriptions + group
            st.copy(subscriptions = subs, selectedGroup = st.selectedGroup ?: group)
        }
    }

    private fun btn(text: String, data: String): InlineKeyboardButton =
        InlineKeyboardButton.CallbackData(text = text, callbackData = data)

    private fun markup(rows: List<List<InlineKeyboardButton>>) = InlineKeyboardMarkup.create(rows)

    private fun menuMarkup(): InlineKeyboardMarkup {
        val today = today()
        return markup(
            listOf(
                listOf(btn("📅 Сегодня", "d:$today"), btn("📅 Завтра", "d:${today.plusDays(1)}")),
                listOf(btn("👥 Группа", "grp"), btn("🔔 Уведомления", "subs")),
            )
        )
    }

    private fun today(): LocalDate = LocalDate.now(zone)

    private fun welcome() = Screen(
        "👋 <b>Привет!</b>\n" +
                "Я показываю расписание из журнала и сообщаю, если оно изменилось.\n\n" +
                "📅 <b>Сегодня / Завтра</b> — расписание выбранной группы\n" +
                "👥 <b>Группа</b> — выбрать, чьё расписание смотреть\n" +
                "🔔 <b>Уведомления</b> — пришлю сообщение, когда расписание на сегодня поменяется",
        menuMarkup(),
    )

    private fun resolveGroup(chatId: Long): GroupConfig? {
        users.get(chatId).selectedGroup?.let { groups[it] }?.let { return it }
        if (groups.size == 1) {
            val only = groups.values.first()
            users.update(chatId) { it.copy(selectedGroup = only.groupName) }
            return only
        }
        return null
    }

    private fun scheduleScreen(chatId: Long, date: LocalDate): Screen {
        val group = resolveGroup(chatId) ?: return groupScreen(chatId)
        val today = today()
        val keyboard = dayKeyboard(date, today)
        val lessons = try {
            service.get(group, date)
        } catch (e: Exception) {
            log.log(Level.SEVERE, "Не удалось получить расписание ${group.groupName} на $date (chat=$chatId)", e)
            return Screen("⚠️ <b>Не удалось получить расписание</b>\nЖурнал сейчас недоступен, попробуйте чуть позже.", keyboard)
        }
        return Screen(Fmt.schedule(group.groupName, date, today, lessons), keyboard)
    }

    private fun dayKeyboard(date: LocalDate, today: LocalDate): InlineKeyboardMarkup {
        val prev = date.minusDays(1)
        val next = date.plusDays(1)
        val nav = mutableListOf<InlineKeyboardButton>(btn("◀ ${Fmt.shortLabel(prev, today)}", "d:$prev"))
        if (date != today) nav += btn("📍 Сегодня", "d:$today")
        nav += btn("${Fmt.shortLabel(next, today)} ▶", "d:$next")
        return markup(listOf(nav, listOf(btn("👥 Группа", "grp"), btn("🔔 Уведомления", "subs"))))
    }

    private fun groupScreen(chatId: Long): Screen {
        val selected = users.get(chatId).selectedGroup
        val rows = config.groups.map {
            listOf(btn((if (it.groupName == selected) "✅ " else "") + it.groupName, "pick:${it.groupName}"))
        }
        return Screen(
            "👥 <b>Выберите группу</b>\nЕё расписание я буду показывать.",
            markup(rows + listOf(listOf(btn("⬅️ Меню", "menu")))),
        )
    }

    private fun subsScreen(chatId: Long): Screen {
        val subs = users.get(chatId).subscriptions
        val rows = config.groups.map {
            val mark = if (it.groupName in subs) "🔔" else "🔕"
            listOf(btn("$mark ${it.groupName}", "tog:${it.groupName}"))
        }
        val text = "🔔 <b>Уведомления об изменениях</b>\n" +
                "Нажмите на группу, чтобы включить или выключить уведомления.\n" +
                "Расписание на сегодня проверяется раз в ${config.checkIntervalMinutes} мин.\n\n" +
                if (subs.isEmpty()) "Сейчас уведомления выключены." else "Включены: " + subs.sorted().joinToString { Fmt.esc(it) }
        return Screen(text, markup(rows + listOf(listOf(btn("⬅️ Меню", "menu")))))
    }
}
