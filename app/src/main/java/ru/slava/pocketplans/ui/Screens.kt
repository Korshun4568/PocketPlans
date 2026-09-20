package ru.slava.pocketplans.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ru.slava.pocketplans.data.*

private fun timestamp(time: Long) = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
private fun amount(cents: Long) = java.math.BigDecimal.valueOf(cents, 2).toPlainString()

@Composable
fun PocketTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(primary = Color(0xFF80D5C6))
        else lightColorScheme(primary = Color(0xFF006B5E), secondary = Color(0xFF49645E), surface = Color(0xFFF7FAF7))
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocketApp(vm: PocketViewModel = hiltViewModel()) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val state by vm.catalog.state.collectAsStateWithLifecycle()
    val products by vm.products.collectAsStateWithLifecycle()
    val personal by vm.personal.collectAsStateWithLifecycle()
    val plans by vm.plans.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val visits by vm.history.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val pricePoints by vm.pricePoints.collectAsStateWithLifecycle()
    val query by vm.catalog.query.collectAsStateWithLifecycle()
    val category by vm.catalog.category.collectAsStateWithLifecycle()
    val favorites by vm.catalog.favorites.collectAsStateWithLifecycle()
    val work by vm.offlineWork.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.notice.value = null } }
    val open: (Int) -> Unit = { nav.navigate("detail/$it") }
    PocketTheme(prefs.dark) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("Pocket Plans", fontWeight = FontWeight.Bold) }, navigationIcon = {
                if (current?.contains("/") == true) TextButton(onClick = { nav.popBackStack() }) { Text("Назад") }
            })
        }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(4.dp)) {
                    listOf("catalog" to "Каталог", "plans" to "Списки", "personal" to "Моё", "presets" to "Фильтры",
                        "history" to "История", "settings" to "Настройки").forEach { (route, label) ->
                        FilterChip(current == route, {
                            nav.navigate(route) { popUpTo("catalog") { saveState = true }; launchSingleTop = true; restoreState = true }
                        }, label = { Text(label) }, modifier = Modifier.padding(horizontal = 3.dp))
                    }
                }
            }
        }) { padding ->
            NavHost(nav, "catalog", modifier = Modifier.padding(padding)) {
                composable("catalog") {
                    CatalogScreen(state, query, category, favorites, products.map { it.category }.distinct().sorted(),
                        { vm.catalog.query.value = it }, { vm.catalog.category.value = it }, { vm.catalog.favorites.value = it },
                        vm.catalog::retry, open, { vm.perform { vm.repository.favorite(it) } }, { name ->
                            vm.perform { vm.repository.savePreset(Preset(name = name, query = query, category = category, favoritesOnly = favorites)) }
                        })
                }
                composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
                    val id = entry.arguments!!.getInt("id")
                    val product = products.find { it.id == id }
                    LaunchedEffect(id, product?.id) { if (product != null) vm.perform { vm.repository.visit(id) } }
                    if (product == null) EmptyMessage("Карточка пока не сохранена", "Вернитесь в каталог и обновите данные.")
                    else DetailScreen(product, personal.find { it.productId == id } ?: Personal(id), plans,
                        pricePoints.filter { it.productId == id }, { note, target, favorite -> vm.perform {
                            vm.repository.savePersonal(id, note, target, favorite); vm.notice.value = "Сохранено на устройстве"
                        } }, { plan -> vm.perform { vm.repository.addToPlan(plan, id); vm.notice.value = "Добавлено в список" } })
                }
                composable("plans") {
                    PlansScreen(plans, entries, products, { nav.navigate("plan/$it") },
                        { id, name, budget -> vm.perform { vm.repository.savePlan(id, name, budget) } },
                        { vm.perform { vm.repository.deletePlan(it) } })
                }
                composable("plan/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    val id = entry.arguments!!.getLong("id")
                    val plan = plans.find { it.id == id }
                    if (plan == null) EmptyMessage("Список не найден", "Возможно, он был удалён.")
                    else PlanScreen(plan, entries.filter { it.planId == id }, products, open,
                        { vm.perform { vm.repository.changeEntry(it) } }, { vm.perform { vm.repository.removeEntry(id, it) } },
                        { nav.navigate("catalog") }, vm::prepareOffline, work.firstOrNull()?.state?.name)
                }
                composable("personal") { PersonalScreen(products, personal, open) }
                composable("history") {
                    HistoryScreen(products, visits, open) { vm.perform { vm.repository.clearHistory() } }
                }
                composable("presets") {
                    PresetsScreen(presets, { vm.catalog.applyPreset(it); nav.navigate("catalog") },
                        { vm.perform { vm.repository.savePreset(it) } }, { vm.perform { vm.repository.deletePreset(it) } })
                }
                composable("settings") {
                    SettingsScreen(prefs, { vm.perform { vm.settings.dark(it) } }, { vm.perform { vm.settings.priceSort(it) } },
                        { vm.perform { vm.settings.background(it) } }, { vm.perform { vm.settings.ttl(it) } },
                        vm::prepareOffline, products.size, work.firstOrNull()?.state?.name, products.maxOfOrNull { it.updatedAt })
                }
            }
        }
    }
}
@Composable
fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable
fun EmptyMessage(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge); Text(subtitle)
    }
}
@Composable
fun CatalogScreen(state: CatalogState, query: String, category: String, favorites: Boolean,
    categories: List<String>, onQuery: (String) -> Unit, onCategory: (String) -> Unit,
    onFavorites: (Boolean) -> Unit, onRetry: () -> Unit, onOpen: (Int) -> Unit,
    onFavorite: (Int) -> Unit, onSavePreset: (String) -> Unit) {
    var presetDialog by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionTitle("Выбирайте осознанно", "Каталог → свой список → бюджет и отметки о покупке")
            OutlinedTextField(query, onQuery, label = { Text("Поиск по сохранённому каталогу") },
                modifier = Modifier.fillMaxWidth().testTag("search"), singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(favorites, { onFavorites(!favorites) }, label = { Text("Избранное") })
                TextButton(onClick = onRetry, enabled = !state.refreshing) { Text("Обновить") }
                TextButton(onClick = { presetDialog = true }) { Text("Сохранить фильтр") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                (listOf("") + categories).forEach { cat ->
                    FilterChip(category == cat, { onCategory(cat) }, label = { Text(cat.ifBlank { "Все категории" }) },
                        modifier = Modifier.padding(end = 6.dp))
                }
            }
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, modifier = Modifier.testTag("retry")) { Text("Повторить") }
            }
        }
        when (state.phase) {
            Phase.Loading -> item { CircularProgressIndicator(Modifier.testTag("loading")) }
            Phase.Empty -> item { EmptyMessage("Ничего не найдено", "Измените фильтры или загрузите каталог из сети.") }
            Phase.Error -> item { EmptyMessage("Каталог ещё не загружен", "Для первой загрузки нужен интернет. Нажмите «Повторить».") }
            Phase.Success -> items(state.items, key = { it.id }) { product ->
                Card(Modifier.fillMaxWidth().testTag("product-${product.id}").clickable { onOpen(product.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(product.category.uppercase(), style = MaterialTheme.typography.labelSmall)
                        Text(product.title, style = MaterialTheme.typography.titleLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(money(product.priceCents), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { onFavorite(product.id) }) { Text(if (product.id in state.favoriteIds) "★ Сохранено" else "☆ Сохранить") }
                        }
                    }
                }
            }
        }
    }
    if (presetDialog) NameDialog("Название фильтра", "", { presetDialog = false }) { onSavePreset(it); presetDialog = false }
}
@Composable
internal fun DetailScreen(product: Product, personal: Personal, plans: List<Plan>, pricePoints: List<PricePoint>,
    onSave: (String, Long?, Boolean) -> Unit, onAdd: (Long) -> Unit) {
    var note by rememberSaveable(product.id, personal.note) { mutableStateOf(personal.note) }
    var target by rememberSaveable(product.id, personal.targetCents) { mutableStateOf(personal.targetCents?.let(::amount) ?: "") }
    var favorite by rememberSaveable(product.id, personal.favorite) { mutableStateOf(personal.favorite) }
    val valid = note.length <= 2000 && (target.isBlank() || priceCents(target) != null)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionTitle(product.title, product.category)
            Text(money(product.priceCents), style = MaterialTheme.typography.headlineMedium)
            Text(product.description)
            Text("Сохранено: ${timestamp(product.updatedAt)} · USD · учебный каталог", style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(note, { note = it }, label = { Text("Моя заметка / решение") }, modifier = Modifier.fillMaxWidth(),
                minLines = 3, isError = note.length > 2000, supportingText = { Text("${note.length}/2000") })
            OutlinedTextField(target, { target = it }, label = { Text("Целевая цена, USD (необязательно)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                isError = target.isNotBlank() && priceCents(target) == null, modifier = Modifier.fillMaxWidth())
            Row { Checkbox(favorite, { favorite = it }); Text("В избранном", Modifier.padding(top = 12.dp)) }
            Button(onClick = { onSave(note, target.takeIf { it.isNotBlank() }?.let(::priceCents), favorite) }, enabled = valid) { Text("Сохранить решение") }
            Text("Для удаления заметки или цели очистите поля и сохраните.", style = MaterialTheme.typography.bodySmall)
        }
        item { SectionTitle("Добавить в мой список", "Повторное добавление увеличивает количество.") }
        if (plans.isEmpty()) item { Text("Сначала создайте список во вкладке «Списки».") }
        items(plans, key = { it.id }) { plan ->
            OutlinedButton(onClick = { onAdd(plan.id) }, modifier = Modifier.fillMaxWidth()) { Text("+ ${plan.name}") }
        }
        item { SectionTitle("История цены", "До 30 последних изменений при обновлениях. Учебное API может долго не менять цены.") }
        items(pricePoints, key = { it.observedAt }) { Text("${timestamp(it.observedAt)} · ${money(it.priceCents)}") }
    }
}
@Composable
internal fun PlansScreen(plans: List<Plan>, entries: List<PlanEntry>, products: List<Product>, onOpen: (Long) -> Unit,
    onSave: (Long?, String, Long) -> Unit, onDelete: (Long) -> Unit) {
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Plan?>(null) }
    var deleting by remember { mutableStateOf<Plan?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionTitle("Мои списки", "Планируйте покупки и контролируйте бюджет.")
            Button(onClick = { editing = null; editor = true }) { Text("Создать список") }
        }
        if (plans.isEmpty()) item { EmptyMessage("Первый план", "Создайте список и добавьте товары из каталога.") }
        items(plans, key = { it.id }) { plan ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(plan.id) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(plan.name, style = MaterialTheme.typography.titleLarge)
                    Text("План: ${money(planTotal(entries.filter { it.planId == plan.id }, products))} / ${money(plan.budgetCents)}")
                    Row {
                        TextButton(onClick = { editing = plan; editor = true }) { Text("Изменить") }
                        TextButton(onClick = { deleting = plan }) { Text("Удалить") }
                    }
                }
            }
        }
    }
    if (editor) PlanDialog(editing, { editor = false }) { name, budget -> onSave(editing?.id, name, budget); editor = false }
    deleting?.let { plan -> ConfirmDelete("Удалить список «${plan.name}»?", { deleting = null }) { onDelete(plan.id); deleting = null } }
}
@Composable
internal fun PlanScreen(plan: Plan, entries: List<PlanEntry>, products: List<Product>, onOpen: (Int) -> Unit,
    onChange: (PlanEntry) -> Unit, onRemove: (Int) -> Unit, onCatalog: () -> Unit, onPrepare: () -> Unit, workStatus: String?) {
    val total = planTotal(entries, products)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionTitle(plan.name, "Отмечайте покупки и меняйте количество даже без сети.")
            Text("Всего: ${money(total)} · бюджет: ${money(plan.budgetCents)}")
            Text(if (total > plan.budgetCents) "Превышение: ${money(total - plan.budgetCents)}" else "Остаток: ${money(plan.budgetCents - total)}",
                color = if (total > plan.budgetCents) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text("Осталось купить: ${money(planTotal(entries, products, true))}")
            TextButton(onClick = onCatalog) { Text("Добавить товары из каталога") }
            OutlinedButton(onClick = onPrepare) { Text("Обновить данные для офлайна") }
            Text(offlineStatus(workStatus), style = MaterialTheme.typography.bodySmall)
        }
        if (entries.isEmpty()) item { EmptyMessage("Список пуст", "Добавьте товары из их карточек.") }
        items(entries, key = { it.productId }) { entry ->
            products.find { it.id == entry.productId }?.let { product ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        TextButton(onClick = { onOpen(product.id) }) { Text(product.title) }
                        Text("${money(product.priceCents)} × ${entry.quantity} = ${money(product.priceCents * entry.quantity)}")
                        Row {
                            TextButton(onClick = { onChange(entry.copy(quantity = entry.quantity - 1)) }, enabled = entry.quantity > 1) { Text("−") }
                            TextButton(onClick = { onChange(entry.copy(quantity = entry.quantity + 1)) }, enabled = entry.quantity < 999) { Text("+") }
                            Checkbox(entry.purchased, { onChange(entry.copy(purchased = it)) })
                            Text("Куплено", Modifier.padding(top = 12.dp))
                        }
                        TextButton(onClick = { onRemove(entry.productId) }) { Text("Убрать из списка") }
                    }
                }
            }
        }
    }
}
@Composable
private fun PersonalScreen(products: List<Product>, personal: List<Personal>, open: (Int) -> Unit) {
    val saved = personal.filter { it.favorite || it.note.isNotBlank() || it.targetCents != null }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SectionTitle("Мои решения", "Заметки, избранное и целевые цены. Учебные цены в USD.")
            Text("Достигнуто целей: ${personal.count { row -> products.find { it.id == row.productId }?.let { targetReached(it, row) } == true }}")
        }
        if (saved.isEmpty()) item { EmptyMessage("Пока пусто", "Откройте товар и сохраните своё решение.") }
        items(saved, key = { it.productId }) { row -> products.find { it.id == row.productId }?.let { product ->
            Card(Modifier.fillMaxWidth().clickable { open(product.id) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(product.title, style = MaterialTheme.typography.titleMedium); Text(money(product.priceCents))
                    row.targetCents?.let { Text("Цель: ${money(it)} · ${if (targetReached(product, row)) "достигнута" else "ожидаем снижения"}") }
                    if (row.note.isNotBlank()) Text(row.note)
                    Text("Цена обновлена: ${timestamp(product.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        } }
    }
}
@Composable
private fun HistoryScreen(products: List<Product>, visits: List<Visit>, open: (Int) -> Unit, clear: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            SectionTitle("Недавно открывали", "История сохраняется между запусками.")
            TextButton(onClick = { confirm = true }, enabled = visits.isNotEmpty()) { Text("Очистить историю") }
        }
        if (visits.isEmpty()) item { EmptyMessage("История пуста", "Откройте карточку в каталоге.") }
        items(visits, key = { it.productId }) { visit -> products.find { it.id == visit.productId }?.let { product ->
            ListItem(headlineContent = { Text(product.title) }, supportingContent = { Text(timestamp(visit.seenAt)) },
                modifier = Modifier.clickable { open(product.id) })
        } }
    }
    if (confirm) ConfirmDelete("Очистить историю?", { confirm = false }) { clear(); confirm = false }
}
@Composable
private fun PresetsScreen(presets: List<Preset>, onApply: (Preset) -> Unit, onRename: (Preset) -> Unit, onDelete: (Long) -> Unit) {
    var editing by remember { mutableStateOf<Preset?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { SectionTitle("Мои фильтры", "Сохранённые поисковые сценарии работают офлайн.") }
        if (presets.isEmpty()) item { EmptyMessage("Фильтров пока нет", "В каталоге нажмите «Сохранить фильтр».") }
        items(presets, key = { it.id }) { preset ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium)
                    Text("${preset.query.ifBlank { "Любой текст" }} · ${preset.category.ifBlank { "Все категории" }} · ${if (preset.favoritesOnly) "Избранное" else "Все товары"}")
                    Row {
                        TextButton(onClick = { onApply(preset) }) { Text("Применить") }
                        TextButton(onClick = { editing = preset }) { Text("Название") }
                        TextButton(onClick = { onDelete(preset.id) }) { Text("Удалить") }
                    }
                }
            }
        }
    }
    editing?.let { preset -> NameDialog("Название фильтра", preset.name, { editing = null }) { onRename(preset.copy(name = it)); editing = null } }
}
private fun offlineStatus(status: String?): String = when (status) {
    "ENQUEUED", "BLOCKED" -> "Обновление запланировано; ожидаем сеть и разрешение Android."
    "RUNNING" -> "Обновляем каталог для офлайна…"
    "SUCCEEDED" -> "Обновление завершено. Карточки доступны без сети."
    "FAILED" -> "Обновление не удалось. Сохранённые данные доступны; попробуйте ещё раз."
    "CANCELLED" -> "Обновление отменено."
    else -> "После загрузки карточки доступны офлайн."
}
@Composable
private fun SettingsScreen(prefs: Preferences, onDark: (Boolean) -> Unit, onSort: (Boolean) -> Unit,
    onBackground: (Boolean) -> Unit, onTtl: (Int) -> Unit, onPrepare: () -> Unit, count: Int, status: String?, last: Long?) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Настройки", "Предпочтения сохраняются на устройстве.") }
        item { Toggle("Тёмная тема", prefs.dark, onDark) }
        item { Toggle("Сортировать по цене", prefs.priceSort, onSort) }
        item { Toggle("Фоновое обновление", prefs.background, onBackground) }
        item {
            Text("Считать каталог устаревшим через:")
            Row { listOf(1, 6, 24).forEach { hours -> FilterChip(prefs.ttlHours == hours, { onTtl(hours) },
                label = { Text("$hours ч") }, modifier = Modifier.padding(end = 8.dp)) } }
            Text("Фоновая задача проверяет актуальность примерно раз в час при сети. Точное время выбирает Android.")
        }
        item {
            Text("Сохранено товаров: $count")
            Text("Последнее обновление: ${last?.let(::timestamp) ?: "ещё не было"}")
            Button(onClick = onPrepare) { Text("Подготовить офлайн-каталог") }; Text(offlineStatus(status))
        }
        item { Text("DummyJSON — учебный каталог, не магазин. Цены в USD. Личные данные не отправляются на сервер. Изображения не используются.") }
    }
}
@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 12.dp)); Switch(checked, onChange)
    }
}
@Composable
private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it }, singleLine = true, isError = name.length > 80)
    }, confirmButton = { TextButton(onClick = { save(name.trim()) }, enabled = name.isNotBlank() && name.length <= 80) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
@Composable
private fun PlanDialog(plan: Plan?, dismiss: () -> Unit, save: (String, Long) -> Unit) {
    var name by rememberSaveable { mutableStateOf(plan?.name ?: "") }
    var budget by rememberSaveable { mutableStateOf(plan?.budgetCents?.let(::amount) ?: "") }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (plan == null) "Новый список" else "Изменить список") }, text = {
        Column {
            OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, isError = name.length > 80)
            OutlinedTextField(budget, { budget = it }, label = { Text("Бюджет, USD") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = budget.isNotBlank() && priceCents(budget) == null)
        }
    }, confirmButton = { TextButton(onClick = { priceCents(budget)?.let { save(name.trim(), it) } },
        enabled = name.isNotBlank() && name.length <= 80 && priceCents(budget) != null) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
@Composable
private fun ConfirmDelete(title: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, confirmButton = { TextButton(onClick = confirm) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Отмена") } })
}
