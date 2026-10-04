package com.driveorderpad

import android.app.Application
import com.driveorderpad.data.FileNotebookRepository
import com.driveorderpad.data.NotebookRepository
import com.driveorderpad.domain.MenuCatalog
import com.driveorderpad.domain.OrderEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class OrderPadApplication : Application() {
    val container by lazy { AppContainer(this) }
}

data class AppDependencies(val menu: MenuCatalog, val repository: NotebookRepository)

/** Manual DI keeps a small single-feature app explicit and avoids service locators in screens. */
class AppContainer(private val application: Application) {
    private val mutex = Mutex()
    private var dependencies: AppDependencies? = null
    suspend fun dependencies(): AppDependencies = mutex.withLock {
        dependencies ?: withContext(Dispatchers.IO) {
            val menu = application.assets.open("menu.json").bufferedReader().use { MenuCatalog.decode(it.readText()) }
            AppDependencies(menu, FileNotebookRepository.create(File(application.filesDir, "orders-v1.json"), OrderEngine(menu)))
        }.also { dependencies = it }
    }
}
