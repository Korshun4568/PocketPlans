package ru.slava.pocketplans

import app.cash.turbine.test
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import ru.slava.pocketplans.data.*
import ru.slava.pocketplans.ui.*

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogModelTest {
    private val product = Product(1, "Phone", "Device", "tech", 500, 0)
    private class FakeSource : CatalogSource {
        override val products = MutableStateFlow<List<Product>>(emptyList())
        override val personal = MutableStateFlow<List<Personal>>(emptyList())
        override val sync = MutableStateFlow(SyncStatus())
        var calls = 0
        var action: suspend FakeSource.() -> SyncResult = { SyncResult.Success }
        override suspend fun refresh(force: Boolean, ttlHours: Int): SyncResult { calls++; return action() }
    }
    @Test fun initialScreenIsLoading() = runTest {
        val model = CatalogModel(FakeSource(), flowOf(Preferences()), backgroundScope)
        assertEquals(Phase.Loading, model.state.value.phase)
    }
    @Test fun fullSequenceLoadingThenSuccess() = runTest {
        val source = FakeSource().apply { action = {
            sync.value = SyncStatus(loading = true); delay(500)
            products.value = listOf(product); sync.value = SyncStatus(); SyncResult.Success
        } }
        val model = CatalogModel(source, flowOf(Preferences()), backgroundScope)
        model.state.map { it.phase }.distinctUntilChanged().test {
            assertEquals(Phase.Loading, awaitItem()); advanceTimeBy(600); runCurrent()
            assertEquals(Phase.Success, awaitItem()); expectNoEvents()
        }
    }
    @Test fun errorRetryReallyIssuesNewRequestAndRecovers() = runTest {
        val source = FakeSource().apply { action = {
            sync.value = SyncStatus(loading = true); delay(500)
            if (calls == 1) { sync.value = SyncStatus(error = "offline"); SyncResult.Retry }
            else { products.value = listOf(product); sync.value = SyncStatus(); SyncResult.Success }
        } }
        val model = CatalogModel(source, flowOf(Preferences()), backgroundScope)
        model.state.map { it.phase }.distinctUntilChanged().test {
            assertEquals(Phase.Loading, awaitItem())
            advanceTimeBy(600); runCurrent(); assertEquals(Phase.Error, awaitItem())
            model.retry(); runCurrent(); assertEquals(Phase.Loading, awaitItem())
            advanceTimeBy(600); runCurrent(); assertEquals(Phase.Success, awaitItem())
            assertEquals(2, source.calls)
        }
    }
    @Test fun noMatchesIsEmptyNotSuccessWithEmptyList() = runTest {
        val source = FakeSource().apply { products.value = listOf(product) }
        val model = CatalogModel(source, flowOf(Preferences()), backgroundScope)
        model.state.map { it.phase }.distinctUntilChanged().test {
            awaitItem(); advanceTimeBy(350); runCurrent(); assertEquals(Phase.Success, awaitItem())
            model.query.value = "missing"; advanceTimeBy(350); runCurrent(); assertEquals(Phase.Empty, awaitItem())
        }
    }
    @Test fun failedRefreshPreservesVisibleCache() = runTest {
        val source = FakeSource().apply { products.value = listOf(product); sync.value = SyncStatus(error = "offline") }
        val model = CatalogModel(source, flowOf(Preferences()), backgroundScope)
        model.state.test {
            awaitItem(); advanceTimeBy(350); runCurrent(); val state = expectMostRecentItem()
            assertEquals(Phase.Success, state.phase); assertEquals(listOf(product), state.items); assertEquals("offline", state.error)
        }
    }
    @Test fun rapidQueriesEmitOnlyLatestAfterDebounce() = runTest {
        val model = CatalogModel(FakeSource(), flowOf(Preferences()), backgroundScope)
        model.filters.test {
            assertEquals("", awaitItem().query)
            model.query.value = "p"; advanceTimeBy(100)
            model.query.value = "ph"; advanceTimeBy(100)
            model.query.value = "phone"; advanceTimeBy(301); runCurrent(); assertEquals("phone", awaitItem().query)
            model.query.value = "phone"; advanceTimeBy(400); runCurrent(); expectNoEvents()
        }
    }
    @Test fun roomFavoritesAndPreferencesReactWithoutManualRefresh() = runTest {
        val other = product.copy(id = 2, title = "Book", priceCents = 2000)
        val source = FakeSource().apply { products.value = listOf(product, other) }
        val prefs = MutableStateFlow(Preferences())
        val model = CatalogModel(source, prefs, backgroundScope)
        model.state.test {
            awaitItem(); advanceTimeBy(350); runCurrent(); assertEquals(listOf(2, 1), expectMostRecentItem().items.map { it.id })
            prefs.value = Preferences(priceSort = true); runCurrent(); assertEquals(listOf(1, 2), awaitItem().items.map { it.id })
            model.favorites.value = true; runCurrent(); assertEquals(Phase.Empty, awaitItem().phase)
            source.personal.value = listOf(Personal(2, favorite = true)); runCurrent(); assertEquals(listOf(2), awaitItem().items.map { it.id })
            assertEquals(1, source.calls)
        }
    }
    @Test fun newerRefreshCancelsObsoleteResult() = runTest {
        var cancelled = false
        val source = FakeSource().apply { action = {
            if (calls == 1) {
                try { delay(1000); products.value = listOf(product.copy(title = "obsolete")) }
                catch (e: CancellationException) { cancelled = true; throw e }
            } else { products.value = listOf(product) }
            SyncResult.Success
        } }
        val model = CatalogModel(source, flowOf(Preferences()), backgroundScope)
        runCurrent(); model.retry(); runCurrent(); advanceTimeBy(1200); runCurrent()
        assertTrue(cancelled); assertEquals("Phone", source.products.value.single().title)
    }
}
