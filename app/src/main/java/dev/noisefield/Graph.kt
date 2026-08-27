package dev.noisefield

import android.app.Application
import android.content.Context
import dev.noisefield.data.NoiseDb
import dev.noisefield.data.Repository
import dev.noisefield.export.Exporter

/**
 * Ручной сервис-локатор. Приложение на четыре экрана и одного пользователя —
 * DI-фреймворк здесь дал бы только лишний слой.
 */
object Graph {

    lateinit var database: NoiseDb
        private set

    lateinit var repository: Repository
        private set

    lateinit var exporter: Exporter
        private set

    fun init(context: Context) {
        if (::database.isInitialized) return
        database = NoiseDb.open(context)
        repository = Repository(database)
        exporter = Exporter(context.applicationContext, repository)
    }
}

class NoiseFieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
