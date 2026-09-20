package ru.slava.pocketplans.ui

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.slava.pocketplans.data.*

data class Filters(val query: String = "", val category: String = "", val favorites: Boolean = false)
enum class Phase { Loading, Error, Empty, Success }
data class CatalogState(val phase: Phase = Phase.Loading, val items: List<Product> = emptyList(),
    val error: String? = null, val refreshing: Boolean = false, val favoriteIds: Set<Int> = emptySet())
fun selectProducts(products: List<Product>, personal: List<Personal>, filters: Filters, priceSort: Boolean): List<Product> {
    val favorites = personal.filter { it.favorite }.map { it.productId }.toSet()
    val selected = products.filter {
        (filters.query.isBlank() || it.title.contains(filters.query.trim(), true) || it.description.contains(filters.query.trim(), true)) &&
            (filters.category.isBlank() || it.category == filters.category) && (!filters.favorites || it.id in favorites)
    }
    return if (priceSort) selected.sortedWith(compareBy<Product> { it.priceCents }.thenBy { it.id })
    else selected.sortedWith(compareBy<Product> { it.title }.thenBy { it.id })
}
fun targetReached(product: Product, personal: Personal): Boolean = personal.targetCents?.let { product.priceCents <= it } ?: false
fun planTotal(entries: List<PlanEntry>, products: List<Product>, onlyPending: Boolean = false): Long {
    val prices = products.associate { it.id to it.priceCents }
    return entries.filter { !onlyPending || !it.purchased }.sumOf { (prices[it.productId] ?: 0) * it.quantity }
}
fun money(cents: Long): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU"))
    .apply { currency = Currency.getInstance("USD") }.format(java.math.BigDecimal.valueOf(cents, 2))

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class CatalogModel(source: CatalogSource, preferences: Flow<Preferences>, scope: CoroutineScope) {
    val query = MutableStateFlow("")
    val category = MutableStateFlow("")
    val favorites = MutableStateFlow(false)
    private val refreshActions = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val filters = combine(query.debounce(300).distinctUntilChanged(), category, favorites, ::Filters)
        .stateIn(scope, SharingStarted.Eagerly, Filters())
    private val local = combine(source.products, source.personal, filters, preferences) { products, personal, filter, prefs ->
        Triple(selectProducts(products, personal, filter, prefs.priceSort), products.isNotEmpty(),
            personal.filter { it.favorite }.map { it.productId }.toSet())
    }
    val state = combine(local, source.sync) { (rows, hasCache, favorites), sync ->
        val phase = when {
            !hasCache && sync.loading -> Phase.Loading
            !hasCache && sync.error != null -> Phase.Error
            rows.isEmpty() -> Phase.Empty
            else -> Phase.Success
        }
        CatalogState(phase, rows, sync.error, sync.loading, favorites)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), CatalogState())
    init {
        scope.launch { refreshActions.onStart { emit(false) }.collectLatest { source.refresh(it, preferences.first().ttlHours) } }
    }
    fun retry() { refreshActions.tryEmit(true) }
    fun applyPreset(preset: Preset) {
        query.value = preset.query; category.value = preset.category; favorites.value = preset.favoritesOnly
    }
}
