package com.vynylrecord.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.vynylrecord.app.core.audio.render.RenderPipeline
import com.vynylrecord.app.core.data.db.VynylDatabase
import com.vynylrecord.app.core.data.prefs.VynylPreferences
import com.vynylrecord.app.core.data.repository.AssetRepository
import com.vynylrecord.app.core.data.repository.RecordRepository
import com.vynylrecord.app.core.render.RenderScheduler
import com.vynylrecord.app.core.storage.FileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The application object: the app's storage, database, preferences and repositories, created once.
 *
 * There is no dependency-injection framework here, and that is deliberate. This app has one graph, no
 * variants, no remote services and no test doubles that need swapping at runtime — a service locator with a
 * single instance and an explicit constructor is smaller, more obvious and easier to reason about than an
 * annotation processor that builds the same object.
 *
 * It is also the only place where the app does work at startup: the bundled sound beds are made known to the
 * database, and any record whose master vanished while the app was not running is reconciled. Both are
 * cheap, both are idempotent, and both happen off the main thread.
 */
class VynylApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        VynylGraph.of(this).start()
    }
}

/**
 * The app's object graph.
 *
 * Everything is created lazily on first use, so a screen that only needs the preferences never opens the
 * database, and a process that only runs a render worker never touches the UI's caches.
 */
class VynylGraph internal constructor(
    private val appContext: Context,
    /** The database to use, when a test wants its own; production always opens the app's own file. */
    private val databaseOverride: VynylDatabase? = null,
) {

    /** A scope for work that must outlive a screen but not the process. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: VynylDatabase by lazy {
        databaseOverride ?: VynylDatabase.get(appContext)
    }

    val preferences: VynylPreferences by lazy { VynylPreferences(appContext) }

    val fileStore: FileStore by lazy { FileStore(appContext) }

    val recordRepository: RecordRepository by lazy { RecordRepository(database.records(), fileStore) }

    val assetRepository: AssetRepository by lazy { AssetRepository(appContext, database.audioAssets(), fileStore) }

    val renderPipeline: RenderPipeline by lazy { RenderPipeline(appContext, fileStore) }

    val renderScheduler: RenderScheduler by lazy { RenderScheduler(appContext) }

    private var started = false

    /**
     * Housekeeping that has to happen once per process, before the first screen is shown.
     *
     * * **Bundled assets are registered** so the Sound Lab can list them and a record can reference them by
     *   id. The sync is keyed on the asset's resource name, so a user's own title, trim and level survive an
     *   app update.
     * * **Records are reconciled**: a master that is no longer on disk — the app was uninstalled while a
     *   render was running, or a file was lost — is marked failed rather than left showing "ready" next to a
     *   record that cannot play.
     *
     * Both failures are logged rather than fatal: the app is still usable with a stale database row, and it
     * is not usable if it refuses to open.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching { assetRepository.syncBundled() }
                .onFailure { error -> Log.w(TAG, "bundled assets could not be registered", error) }
            runCatching { recordRepository.reconcileOnStartup() }
                .onFailure { error -> Log.w(TAG, "records could not be reconciled", error) }
        }
    }

    companion object {
        @Volatile
        private var instance: VynylGraph? = null

        /** The process-wide graph. */
        fun of(context: Context): VynylGraph {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: VynylGraph(context.applicationContext).also { instance = it }
            }
        }

        /**
         * A graph over a caller's own database, for tests and for instrumentation.
         *
         * The app has exactly one graph in production; a test that needs a fresh in-memory database gets one
         * without any global state being mutated, which is why this is a separate constructor rather than a
         * setter on the singleton.
         */
        fun forTest(context: Context, database: VynylDatabase): VynylGraph =
            VynylGraph(context.applicationContext, database)

        private const val TAG = "VynylApplication"
    }
}
