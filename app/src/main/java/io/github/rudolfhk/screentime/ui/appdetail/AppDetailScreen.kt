package io.github.rudolfhk.screentime.ui.appdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.rudolfhk.screentime.data.StatsBundle
import io.github.rudolfhk.screentime.ui.components.AppIcon
import io.github.rudolfhk.screentime.ui.components.EmptyHint
import io.github.rudolfhk.screentime.ui.components.SectionCard
import io.github.rudolfhk.screentime.ui.components.StatLine
import io.github.rudolfhk.screentime.ui.stats.DistributionCard
import io.github.rudolfhk.screentime.ui.stats.HabitsCard
import io.github.rudolfhk.screentime.ui.stats.HeatmapCard
import io.github.rudolfhk.screentime.ui.stats.PeriodControls
import io.github.rudolfhk.screentime.ui.stats.SummaryCard
import io.github.rudolfhk.screentime.ui.stats.TrendCard
import io.github.rudolfhk.screentime.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class AppDetailViewModel(c: AppContainer, val packageName: String, initial: PeriodSelection) : ViewModel() {
    val selection = MutableStateFlow(initial)
    val bundle: StateFlow<StatsBundle?> = selection
        .flatMapLatest { c.stats.stats(it, setOf(packageName), null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
fun AppDetailScreen(packageName: String, initial: PeriodSelection, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: AppDetailViewModel = viewModel(key = "app-$packageName") { AppDetailViewModel(context.container, packageName, initial) }
    val selection by vm.selection.collectAsStateWithLifecycle()
    val bundle by vm.bundle.collectAsStateWithLifecycle()
    val info = bundle?.apps?.get(packageName)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            AppIcon(packageName, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(info?.label ?: packageName, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    packageName + " · " + AppCategories.label(info?.category ?: AppCategories.UNDEFINED),
                    fontSize = 11.sp, color = Palette.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PeriodControls(selection, bundle?.stats?.range, onSelect = { vm.selection.value = it })

        val b = bundle ?: run { EmptyHint("Loading…"); return@Column }
        val st = b.stats
        SummaryCard(st, b.previous, b.settings.countNotifications)
        TrendCard(st, 0)
        HeatmapCard(st)
        DistributionCard(st)
        HabitsCard(st)
        val usage = st.apps.firstOrNull()
        if (usage != null) {
            SectionCard("Details") {
                val tf = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
                StatLine("Last used", usage.lastUsed?.let { tf.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "–")
                StatLine("Longest single session", Format.durationPrecise(usage.longestSession))
                StatLine("Avg per launch", Format.durationPrecise(usage.avgSession))
                if (b.settings.countNotifications && usage.launches > 0) {
                    StatLine("Notifications per launch", "%.1f".format(usage.notifications.toDouble() / usage.launches))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
