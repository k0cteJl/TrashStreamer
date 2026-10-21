package ru.k0cteJl

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val configPath = Path.of(args.firstOrNull() ?: "config.json")

    val config = try {
        ConfigLoader.load(configPath)
    } catch (e: Exception) {
        System.err.println("Ошибка конфигурации: ${e.message}")
        exitProcess(1)
    }

    val dataDir = Path.of(config.dataDir).also { Files.createDirectories(it) }
    AppLog.setup(dataDir.resolve("logs"))
    val log = Logger.getLogger("Main")
    log.info("Старт. Конфиг: ${configPath.toAbsolutePath()}, группы: ${config.groups.joinToString { it.groupName }}")

    val users = UserStore(dataDir)
    val snapshots = SnapshotStore(dataDir)
    val service = ScheduleService(TopAcademyClient())
    val ui = BotUi(config, service, users)
    val watcher = ScheduleWatcher(config, service, snapshots, users, send = { id, text -> ui.send(id, text) }, alert = ui::alertAdmin)
    val backups = BackupService(dataDir, config.backupKeep)

    val counter = AtomicInteger()
    val scheduler = Executors.newScheduledThreadPool(2) { Thread(it, "sched-${counter.incrementAndGet()}") }

    fun guarded(name: String, task: () -> Unit) = Runnable {
        try {
            task()
        } catch (t: Throwable) {
            log.log(Level.SEVERE, "Плановая задача '$name' упала", t)
        }
    }

    ui.start()
    scheduler.scheduleWithFixedDelay(guarded("watcher") { watcher.checkAll() }, 20, config.checkIntervalMinutes * 60, TimeUnit.SECONDS)
    scheduler.scheduleWithFixedDelay(guarded("backup") { backups.run() }, 60, config.backupIntervalMinutes * 60, TimeUnit.SECONDS)

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Останавливаюсь…")
        scheduler.shutdown()
        ui.stop()
    })
}
