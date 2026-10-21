package ru.k0cteJl

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.logging.*

object AppLog {
    fun setup(dir: Path) {
        Files.createDirectories(dir)

        val formatter = object : Formatter() {
            override fun format(r: LogRecord): String {
                val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(r.millis), ZoneId.systemDefault())
                val sb = StringBuilder()
                sb.append(time.toString().replace('T', ' '))
                    .append(" [").append(r.level).append("] [")
                    .append(Thread.currentThread().name).append("] ")
                    .append(r.loggerName?.substringAfterLast('.') ?: "-")
                    .append(": ").append(formatMessage(r)).append('\n')
                r.thrown?.let {
                    val sw = StringWriter()
                    it.printStackTrace(PrintWriter(sw))
                    sb.append(sw)
                }
                return sb.toString()
            }
        }

        val root = Logger.getLogger("")
        root.handlers.toList().forEach { root.removeHandler(it) }
        root.level = Level.INFO

        root.addHandler(ConsoleHandler().apply { this.formatter = formatter; level = Level.INFO })
        root.addHandler(
            FileHandler(dir.resolve("bot-%g.log").toString(), 5_000_000, 7, true).apply {
                this.formatter = formatter
                encoding = "UTF-8"
                level = Level.INFO
            }
        )

        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Logger.getLogger("Uncaught").log(Level.SEVERE, "Необработанное исключение в потоке ${t.name}", e)
        }
    }
}
