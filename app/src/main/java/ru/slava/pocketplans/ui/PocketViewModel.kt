package ru.slava.pocketplans.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ru.slava.pocketplans.SyncScheduler
import ru.slava.pocketplans.data.*

@HiltViewModel
class PocketViewModel @Inject constructor(val repository: Repository, val settings: Settings,
    private val scheduler: SyncScheduler) : ViewModel() {
    private fun <T> Flow<List<T>>.screen() = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val products = repository.products.screen()
    val personal = repository.personal.screen()
    val plans = repository.plans.screen()
    val entries = repository.entries.screen()
    val history = repository.history.screen()
    val presets = repository.presets.screen()
    val pricePoints = repository.pricePoints.screen()
    val preferences = settings.values.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Preferences())
    val offlineWork = scheduler.offlineWork().screen()
    val catalog = CatalogModel(repository, settings.values, viewModelScope)
    val notice = MutableStateFlow<String?>(null)
    fun perform(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { notice.value = "Не удалось сохранить. Проверьте ввод и повторите." }
        }
    }
    fun prepareOffline() { scheduler.prepareOffline() }
}
