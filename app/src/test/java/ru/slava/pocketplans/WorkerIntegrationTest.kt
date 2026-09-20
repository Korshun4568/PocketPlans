package ru.slava.pocketplans

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import ru.slava.pocketplans.data.*
import ru.slava.pocketplans.data.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class WorkerIntegrationTest {
    private lateinit var db: PocketDatabase
    private lateinit var settings: Settings
    private lateinit var repository: Repository
    private lateinit var file: File
    private lateinit var scope: CoroutineScope
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val api = RepositoryIntegrationTest.FakeApi()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, PocketDatabase::class.java).allowMainThreadQueries().build()
        file = File(context.cacheDir, "worker-${System.nanoTime()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = Settings(PreferenceDataStoreFactory.create(scope = scope) { file })
        repository = Repository(db, api, Clock { 100000L })
    }
    @After fun cleanup() { scope.cancel(); db.close(); file.delete() }
    private fun worker(force: Boolean = false): CatalogWorker = TestListenableWorkerBuilder<CatalogWorker>(context)
        .setInputData(workDataOf("force" to force)).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                CatalogWorker(appContext, workerParameters, repository, settings)
        }).build()
    @Test fun periodicDisabledSkipsApi() = runTest {
        settings.background(false); assertEquals(ListenableWorker.Result.success(), worker().doWork()); assertEquals(0, api.calls)
    }
    @Test fun explicitOfflinePreparationWorksWhenPeriodicDisabled() = runTest {
        settings.background(false); assertEquals(ListenableWorker.Result.success(), worker(true).doWork())
        assertEquals(1, repository.products.first().size)
    }
    @Test fun networkFailureRequestsRetry() = runTest {
        api.fail = true; assertEquals(ListenableWorker.Result.retry(), worker(true).doWork())
    }
    @Test fun settingsAreReadBackFromDataStore() = runTest {
        settings.dark(true); settings.ttl(6); settings.priceSort(true); val value = settings.values.first()
        assertTrue(value.dark); assertTrue(value.priceSort); assertEquals(6, value.ttlHours)
    }
}
