package ru.slava.pocketplans

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.slava.pocketplans.data.*

@HiltAndroidApp
class PocketApplication : Application(), Configuration.Provider {
    @Inject lateinit var factory: HiltWorkerFactory
    @Inject lateinit var settings: Settings
    @Inject lateinit var scheduler: SyncScheduler
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val workManagerConfiguration: Configuration get() = Configuration.Builder().setWorkerFactory(factory).build()
    override fun onCreate() {
        super.onCreate()
        scope.launch { settings.values.map { it.background }.distinctUntilChanged().collect { scheduler.periodic(it) } }
    }
}
@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun periodic(enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) { manager.cancelUniqueWork("catalog-periodic"); return }
        val work = PeriodicWorkRequestBuilder<CatalogWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork("catalog-periodic", ExistingPeriodicWorkPolicy.KEEP, work)
    }
    fun prepareOffline() {
        val work = OneTimeWorkRequestBuilder<CatalogWorker>().setInputData(workDataOf("force" to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("offline-pack", ExistingWorkPolicy.KEEP, work)
    }
    fun offlineWork() = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("offline-pack")
}
@HiltWorker
class CatalogWorker @AssistedInject constructor(@Assisted context: Context, @Assisted params: WorkerParameters,
    private val repository: Repository, private val settings: Settings) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = settings.values.first()
        val force = inputData.getBoolean("force", false)
        if (!force && !prefs.background) return Result.success()
        return when (repository.refresh(force, prefs.ttlHours)) {
            SyncResult.Success, SyncResult.Skipped -> Result.success()
            SyncResult.Retry -> if (runAttemptCount < 4) Result.retry() else Result.failure()
            SyncResult.Failure -> Result.failure()
        }
    }
}
