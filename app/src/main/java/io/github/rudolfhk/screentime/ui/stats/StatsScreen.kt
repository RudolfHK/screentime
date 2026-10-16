package io.github.rudolfhk.screentime.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rudolfhk.screentime.AppContainer
import io.github.rudolfhk.screentime.container
import io.github.rudolfhk.screentime.core.AppCategories
import io.github.rudolfhk.screentime.core.Format
import io.github.rudolfhk.screentime.core.PeriodSelection
import io.github.rudolfhk.screentime.core.RangeKind
import io.github.rudolfhk.screentime.data.AppInfoEntity
import io.github.rudolfhk.screentime.data.StatsBundle
import io.github.rudolfhk.screentime.ui.components.AppIcon
import io.github.rudolfhk.screentime.ui.components.EmptyHint
import io.github.rudolfhk.screentime.ui.components.Pill
import io.github.rudolfhk.screentime.ui.components.SectionCard
import io.github.rudolfhk.screentime.ui.components.UsageRow
import io.github.rudolfhk.screentime.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate

data class StatsFilter(
    val selection: PeriodSelection = PeriodSelection(RangeKind.WEEK, LocalDate.now()),
    val packages: Set<String>? = null,
    val category: Int? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(StatsFilter())

    val bundle: StateFlow<StatsBundle?> = filter
        .flatMapLatest { c.stats.stats(it.selection, it.packages, it.category) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val apps: StateFlow<List<AppInfoEntity>> = c.db.usageDao().apps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun select(sel: PeriodSelection) = filter.update { it.copy(selection = sel) }
    fun setPackages(p: Set<String>?) = filter.update { it.copy(packages = p?.takeIf { s -> s.isNotEmpty() }) }
    fun setCategory(c: Int?) = filter.update { it.copy(category = c) }
}

@Composable
fun StatsScreen(onOpenApp: (String, PeriodSelection) -> Unit) {
    val context = LocalContext.current
    val vm: StatsViewModel = viewModel { StatsViewModel(context.container) }
    val filter by vm.filter.collectAsStateWithLifecycle()
    val bundle by vm.bundle.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    var showAppPicker by remember { mutableStateOf(false) }
    var showAllApps by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Stats", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        PeriodControls(filter.selection, bundle?.stats?.range, onSelect = vm::select)
        FilterRow(filter, apps, onPickApps = { showAppPicker = true }, onCategory = vm::setCategory, onClear = {
            vm.setPackages(null); vm.setCategory(null)
        })

        val b = bundle
        if (b == null) {
            EmptyHint("Loading…")
            return@Column
        }
        val st = b.stats
        if (b.firstTrackedAt == null) {
            SectionCard { EmptyHint("No data yet. Switch tracking on in the Tracker tab.") }
            return@Column
        }

        SummaryCard(st, b.previous, b.settings.countNotifications)
        TrendCard(st, b.settings.dailyGoalMinutes * 60_000L)
        HeatmapCard(st)
        HabitsCard(st)
        DistributionCard(st)
        if (filter.category == null) CategoriesCard(st, onSelectCategory = vm::setCategory)

        SectionCard("Apps", trailing = { Text("${st.apps.size}", color = Palette.TextDim, fontSize = 12.sp) }) {
            if (st.apps.isEmpty()) EmptyHint("No app usage in this period.")
            val shown = if (showAllApps) st.apps else st.apps.take(15)
            val maxT = st.apps.firstOrNull()?.total ?: 1
            val total = st.apps.sumOf { it.total }.coerceAtLeast(1)
            shown.forEach { a ->
                UsageRow(
                    packageName = a.packageName,
                    label = b.apps[a.packageName]?.label ?: a.packageName,
                    value = Format.duration(a.total),
                    fraction = a.total.toFloat() / maxT,
                    sub = "${Format.percent(a.total.toDouble() / total)} · ${a.launches} launches · ⌀ ${Format.durationPrecise(a.avgSession)}" +
                        if (a.notifications > 0) " · ${a.notifications} notif." else "",
                    onClick = { onOpenApp(a.packageName, filter.selection) },
                )
            }
            if (st.apps.size > 15) {
                TextButton(onClick = { showAllApps = !showAllApps }) {
                    Text(if (showAllApps) "Show less" else "Show all ${st.apps.size}", color = Palette.Accent)
                }
            }
        }
        CoverageCard(st)
        Spacer(Modifier.height(16.dp))
    }

    if (showAppPicker) {
        AppPickerSheet(
            title = "Filter by apps",
            apps = apps,
            initial = filter.packages ?: emptySet(),
            onDismiss = { showAppPicker = false },
            onApply = { vm.setPackages(it); showAppPicker = false },
        )
    }
}

@Composable
private fun FilterRow(
    filter: StatsFilter,
    apps: List<AppInfoEntity>,
    onPickApps: () -> Unit,
    onCategory: (Int?) -> Unit,
    onClear: () -> Unit,
) {
    var catMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pk = filter.packages
        val appLabel = when {
            pk == null -> "All apps"
            pk.size == 1 -> apps.firstOrNull { it.packageName == pk.first() }?.label ?: pk.first()
            else -> "${pk.size} apps"
        }
        Pill(appLabel, pk != null, icon = Icons.Filled.Apps, onClick = onPickApps)
        Box {
            Pill(filter.category?.let(AppCategories::label) ?: "All categories", filter.category != null, icon = Icons.Filled.Category) { catMenu = true }
            DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                DropdownMenuItem(text = { Text("All categories") }, onClick = { onCategory(null); catMenu = false })
                AppCategories.all.forEach { c ->
                    DropdownMenuItem(text = { Text(AppCategories.label(c)) }, onClick = { onCategory(c); catMenu = false })
                }
            }
        }
        if (filter.packages != null || filter.category != null) {
            Pill("Clear", false, icon = Icons.Filled.Close, onClick = onClear)
        }
    }
}

/** Multi-select list of apps with search. Used for filters and exclusions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(
    title: String,
    apps: List<AppInfoEntity>,
    initial: Set<String>,
    onDismiss: () -> Unit,
    onApply: (Set<String>) -> Unit,
    applyLabel: String = "Apply",
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.Surface) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = emptySet() }) { Text("None", color = Palette.TextDim) }
                TextButton(onClick = { onApply(selected) }) { Text(applyLabel, color = Palette.Accent) }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            val list = apps
                .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
                .sortedWith(compareByDescending<AppInfoEntity> { it.packageName in initial }.thenBy { it.label.lowercase() })
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(list, key = { it.packageName }) { app ->
                    val checked = app.packageName in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = if (checked) selected - app.packageName else selected + app.packageName }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app.packageName, 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontSize = 14.sp)
                            Text(app.packageName, fontSize = 11.sp, color = Palette.TextFaint)
                        }
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { selected = if (it) selected + app.packageName else selected - app.packageName },
                            colors = CheckboxDefaults.colors(checkedColor = Palette.Accent, checkmarkColor = Palette.Bg),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
