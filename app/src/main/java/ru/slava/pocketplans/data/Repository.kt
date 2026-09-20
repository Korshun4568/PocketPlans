package ru.slava.pocketplans.data

import androidx.room.withTransaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import retrofit2.http.GET
import retrofit2.http.Query

data class ProductDto(val id: Int, val title: String, val description: String, val category: String, val price: BigDecimal)
data class ProductPage(val products: List<ProductDto>, val total: Int, val skip: Int, val limit: Int)
interface CatalogApi {
    @GET("products") suspend fun products(@Query("limit") limit: Int = 100, @Query("skip") skip: Int = 0): ProductPage
}
fun interface Clock { fun now(): Long }
fun priceCents(raw: String): Long? = try {
    val value = raw.trim().replace(',', '.').toBigDecimal()
    if (value.signum() < 0 || value > BigDecimal("1000000000")) null
    else value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
} catch (_: ArithmeticException) { null } catch (_: NumberFormatException) { null }
data class SyncStatus(val loading: Boolean = false, val error: String? = null)
enum class SyncResult { Success, Skipped, Retry, Failure }
interface CatalogSource {
    val products: Flow<List<Product>>
    val personal: Flow<List<Personal>>
    val sync: StateFlow<SyncStatus>
    suspend fun refresh(force: Boolean = true, ttlHours: Int = 24): SyncResult
}
@Singleton
class Repository @Inject constructor(private val db: PocketDatabase, private val api: CatalogApi,
    private val clock: Clock) : CatalogSource {
    private val dao = db.dao()
    private val lock = Mutex()
    private val status = MutableStateFlow(SyncStatus())
    override val sync = status.asStateFlow()
    override val products = dao.products()
    override val personal = dao.personal()
    val plans = dao.plans()
    val entries = dao.entries()
    val history = dao.history()
    val presets = dao.presets()
    val pricePoints = dao.pricePoints()
    override suspend fun refresh(force: Boolean, ttlHours: Int): SyncResult = lock.withLock {
        val last = dao.syncMeta()?.successAt
        if (!force && last != null && clock.now() - last in 0 until ttlHours * 3_600_000L) return@withLock SyncResult.Skipped
        status.value = SyncStatus(loading = true)
        try {
            val all = mutableListOf<Product>()
            var offset = 0
            do {
                val page = api.products(skip = offset)
                if (page.products.isEmpty() && offset < page.total) throw IOException("Incomplete page")
                all += page.products.map { Product(it.id, it.title, it.description, it.category,
                    it.price.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact(), clock.now()) }
                offset += page.products.size
                if (offset > 10000) throw IOException("Catalog too large")
            } while (offset < page.total)
            db.withTransaction {
                val old = dao.allProducts().associateBy { it.id }
                // Upsert retains parent identity and therefore all personal relations.
                dao.upsertProducts(all)
                all.filter { old[it.id]?.priceCents != it.priceCents }.forEach {
                    dao.pricePoint(PricePoint(it.id, it.updatedAt, it.priceCents)); dao.trimPrices(it.id)
                }
                dao.syncMeta(SyncMeta(successAt = clock.now()))
            }
            status.value = SyncStatus(); SyncResult.Success
        } catch (e: CancellationException) {
            status.value = SyncStatus(); throw e
        } catch (e: Exception) {
            status.value = SyncStatus(error = "Не удалось обновить каталог. Сохранённые данные доступны.")
            if (e is IOException || (e is HttpException && (e.code() >= 500 || e.code() == 429))) SyncResult.Retry else SyncResult.Failure
        }
    }
    suspend fun savePersonal(id: Int, note: String, target: Long?, favorite: Boolean) {
        require(note.length <= 2000 && (target == null || target >= 0))
        dao.upsertPersonal(Personal(id, note.trim(), target, favorite))
    }
    suspend fun favorite(id: Int) = db.withTransaction {
        val old = dao.getPersonal(id) ?: Personal(id); dao.upsertPersonal(old.copy(favorite = !old.favorite))
    }
    suspend fun savePlan(id: Long?, name: String, budget: Long) {
        require(name.trim().isNotEmpty() && name.length <= 80 && budget >= 0)
        if (id == null) dao.insertPlan(Plan(name = name.trim(), budgetCents = budget, createdAt = clock.now()))
        else dao.updatePlan(id, name.trim(), budget)
    }
    suspend fun deletePlan(id: Long) = dao.deletePlan(id)
    suspend fun addToPlan(plan: Long, product: Int) = db.withTransaction {
        val old = dao.entry(plan, product)
        dao.upsertEntry(old?.copy(quantity = (old.quantity + 1).coerceAtMost(999)) ?: PlanEntry(plan, product))
    }
    suspend fun changeEntry(entry: PlanEntry) { require(entry.quantity in 1..999); dao.upsertEntry(entry) }
    suspend fun removeEntry(plan: Long, product: Int) = dao.deleteEntry(plan, product)
    suspend fun visit(id: Int) { if (dao.getProduct(id) != null) dao.visit(Visit(id, clock.now())) }
    suspend fun clearHistory() = dao.clearHistory()
    suspend fun savePreset(preset: Preset) {
        require(preset.name.trim().isNotEmpty() && preset.name.length <= 80)
        dao.savePreset(preset.copy(name = preset.name.trim()))
    }
    suspend fun deletePreset(id: Long) = dao.deletePreset(id)
}
