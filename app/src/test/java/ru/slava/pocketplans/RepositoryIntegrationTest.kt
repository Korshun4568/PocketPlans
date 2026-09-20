package ru.slava.pocketplans

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.math.BigDecimal
import ru.slava.pocketplans.data.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class RepositoryIntegrationTest {
    private lateinit var db: PocketDatabase
    private lateinit var repository: Repository
    private var now = 100000L
    private val api = FakeApi()
    class FakeApi : CatalogApi {
        var calls = 0
        var fail = false
        var price = BigDecimal("12.34")
        override suspend fun products(limit: Int, skip: Int): ProductPage {
            calls++; if (fail) throw IOException("offline")
            return ProductPage(listOf(ProductDto(7, "Phone", "Device", "tech", price)), 1, 0, 100)
        }
    }
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), PocketDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = Repository(db, api, Clock { now })
    }
    @After fun close() = db.close()
    @Test fun apiRefreshActuallyPersistsInRoom() = runTest {
        assertEquals(SyncResult.Success, repository.refresh()); assertEquals(1234L, db.dao().getProduct(7)!!.priceCents)
        assertEquals("Phone", repository.products.first().single().title)
    }
    @Test fun failedSyncRetainsProductsAndPersonalData() = runTest {
        repository.refresh(); repository.savePersonal(7, "Моя заметка", 1000, true); api.fail = true
        assertEquals(SyncResult.Retry, repository.refresh()); assertEquals(1, repository.products.first().size)
        assertEquals("Моя заметка", repository.personal.first().single().note)
    }
    @Test fun refreshingParentDoesNotCascadeDeleteRelations() = runTest {
        repository.refresh(); repository.savePlan(null, "План", 5000)
        val plan = repository.plans.first().single()
        repository.addToPlan(plan.id, 7); repository.savePersonal(7, "keep", null, true); repository.refresh()
        assertEquals(1, repository.entries.first().size); assertEquals("keep", repository.personal.first().single().note)
    }
    @Test fun addingTwiceIncreasesQuantityWithoutDuplicate() = runTest {
        repository.refresh(); repository.savePlan(null, "План", 5000); val id = repository.plans.first().single().id
        repository.addToPlan(id, 7); repository.addToPlan(id, 7); assertEquals(2, repository.entries.first().single().quantity)
    }
    @Test fun deletePlanCascadesOnlyItsEntries() = runTest {
        repository.refresh(); repository.savePlan(null, "План", 5000); val id = repository.plans.first().single().id
        repository.addToPlan(id, 7); repository.deletePlan(id)
        assertTrue(repository.entries.first().isEmpty()); assertEquals(1, repository.products.first().size)
    }
    @Test fun historyDeduplicatesAndUpdatesTimestamp() = runTest {
        repository.refresh(); repository.visit(7); now += 100; repository.visit(7)
        assertEquals(now, repository.history.first().single().seenAt)
    }
    @Test fun freshCacheSkipsNetworkAndExpiredCacheRefreshes() = runTest {
        repository.refresh(); assertEquals(SyncResult.Skipped, repository.refresh(false, 1)); assertEquals(1, api.calls)
        now += 3600001; assertEquals(SyncResult.Success, repository.refresh(false, 1)); assertEquals(2, api.calls)
    }
    @Test fun savedPresetCanBeUpdatedAndDeleted() = runTest {
        repository.savePreset(Preset(name = "Техника", query = "phone", category = "tech", favoritesOnly = true))
        val preset = repository.presets.first().single(); repository.savePreset(preset.copy(name = "Телефон"))
        assertEquals("Телефон", repository.presets.first().single().name)
        repository.deletePreset(preset.id); assertTrue(repository.presets.first().isEmpty())
    }
    @Test fun priceHistoryRecordsChangesButNotUnchangedRefreshes() = runTest {
        repository.refresh(); now += 1000; repository.refresh(); assertEquals(1, repository.pricePoints.first().size)
        now += 1000; api.price = BigDecimal("9.00"); repository.refresh()
        assertEquals(listOf(900L, 1234L), repository.pricePoints.first().map { it.priceCents })
    }
    @Test fun secondPageFailureDoesNotCommitPartialCatalogOrAdvanceSyncTime() = runTest {
        repository.refresh(); val lastSuccess = db.dao().syncMeta()!!.successAt; now += 1000
        val paginated = object : CatalogApi {
            override suspend fun products(limit: Int, skip: Int): ProductPage {
                if (skip > 0) throw IOException("page two offline")
                return ProductPage(listOf(ProductDto(99, "New", "New item", "tech", BigDecimal.ONE)), 2, 0, 1)
            }
        }
        assertEquals(SyncResult.Retry, Repository(db, paginated, Clock { now }).refresh())
        assertEquals(listOf(7), repository.products.first().map { it.id }); assertEquals(lastSuccess, db.dao().syncMeta()!!.successAt)
    }
}
