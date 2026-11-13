package io.github.rudolfhk.screentime.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rudolfhk.screentime.AppContainer
import io.github.rudolfhk.screentime.BuildConfig
import io.github.rudolfhk.screentime.container
import io.github.rudolfhk.screentime.core.BackupFormatException
import io.github.rudolfhk.screentime.core.Format
import io.github.rudolfhk.screentime.data.AppInfoEntity
import io.github.rudolfhk.screentime.data.Settings
import io.github.rudolfhk.screentime.tracking.Permissions
import io.github.rudolfhk.screentime.ui.PermissionState
import io.github.rudolfhk.screentime.ui.components.ControlShape
import io.github.rudolfhk.screentime.ui.components.Divider
import io.github.rudolfhk.screentime.ui.components.Gutter
import io.github.rudolfhk.screentime.ui.components.NavRow
import io.github.rudolfhk.screentime.ui.components.Panel
import io.github.rudolfhk.screentime.ui.components.ScreenHeader
import io.github.rudolfhk.screentime.ui.components.SegmentedControl
import io.github.rudolfhk.screentime.ui.components.StatusDot
import io.github.rudolfhk.screentime.ui.stats.AppPickerSheet
import io.github.rudolfhk.screentime.ui.theme.LabelStyle
import io.github.rudolfhk.screentime.ui.theme.MetaStyle
import io.github.rudolfhk.screentime.ui.theme.NumberStyle
import io.github.rudolfhk.screentime.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun consumeMessage() { _message.value = null }

    fun setInterval(min: Int) = viewModelScope.launch { c.tracking.reschedule(min) }
    fun setGoal(min: Int) = viewModelScope.launch { c.settings.setDailyGoal(min.coerceIn(0, 16 * 60)) }
    fun setRetention(days: Int) = viewModelScope.launch {
        c.settings.setRetentionDays(days)
        if (days > 0) c.tracking.sync()
    }
    fun setExcluded(p: Set<String>) = viewModelScope.launch { c.settings.setExcluded(p) }
    fun setCountNotifications(on: Boolean) = viewModelScope.launch { c.settings.setCountNotifications(on) }
    fun setAppLock(on: Boolean) = viewModelScope.launch { c.settings.setAppLock(on) }

    /** Apps seen in usage data plus launchable apps on the device, for the exclusion picker. */
    suspend fun pickerApps(): List<AppInfoEntity> = withContext(Dispatchers.IO) {
        val known = c.db.usageDao().apps().first().associateBy { it.packageName }.toMutableMap()
        val now = System.currentTimeMillis()
        (c.appInfo.launchablePackages() + c.settings.current().excludedPackages).forEach { pkg ->
            if (pkg !in known) known[pkg] = c.appInfo.resolve(pkg, now) ?: c.appInfo.fallback(pkg, now)
        }
        known.values.toList()
    }

    private fun runTask(label: String, block: suspend () -> String) = viewModelScope.launch {
        _busy.value = true
        _message.value = try {
            block()
        } catch (e: BackupFormatException) {
            "$label failed: ${e.message}"
        } catch (e: Exception) {
            "$label failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            _busy.value = false
        }
    }

    fun exportJson(uri: Uri) = runTask("Export") { "Exported ${c.backup.exportJson(uri)} records" }
    fun exportCsv(uri: Uri) = runTask("CSV export") { c.backup.exportCsvZip(uri); "CSV archive saved" }
    fun import(uri: Uri) = runTask("Import") {
        val r = c.backup.import(uri)
        c.tracking.refreshAppInfo()
        if (r.total == 0) "Nothing new – all records were already present"
        else "Imported ${r.screenSessions} screen sessions, ${r.appSessions} app sessions, ${r.unlocks} unlocks, ${r.notifications} notifications"
    }
    fun clearAll() = runTask("Delete") { c.tracking.clearAllData(); "All usage data deleted" }
}

/** Callbacks for the stateless settings content, so it can be rendered in screenshot tests. */
class SettingsActions(
    val setInterval: (Int) -> Unit = {},
    val setGoal: (Int) -> Unit = {},
    val setRetention: (Int) -> Unit = {},
    val openExclusions: () -> Unit = {},
    val deleteAll: () -> Unit = {},
    val toggleNotifications: (Boolean) -> Unit = {},
    val toggleAppLock: (Boolean) -> Unit = {},
    val exportJson: () -> Unit = {},
    val exportCsv: () -> Unit = {},
    val importJson: () -> Unit = {},
    val openUsageAccess: () -> Unit = {},
    val openBattery: () -> Unit = {},
)

@Composable
fun SettingsScreen(permissions: PermissionState) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel { SettingsViewModel(context.container) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var excludePicker by remember { mutableStateOf<List<AppInfoEntity>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportJson) }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::exportCsv) }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }

    val s = settings ?: return
    val date = LocalDate.now().toString()
    val actions = SettingsActions(
        setInterval = { vm.setInterval(it) },
        setGoal = { vm.setGoal(it) },
        setRetention = { vm.setRetention(it) },
        openExclusions = { scope.launch { excludePicker = vm.pickerApps() } },
        deleteAll = { confirmDelete = true },
        toggleNotifications = { on ->
            if (on && !permissions.notificationAccess) Permissions.openNotificationAccessSettings(context)
            vm.setCountNotifications(on)
        },
        toggleAppLock = { on ->
            if (on) {
                val res = BiometricManager.from(context).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                if (res == BiometricManager.BIOMETRIC_SUCCESS) vm.setAppLock(true)
                else scope.launch { snackbar.showSnackbar("Set up a screen lock on this device first") }
            } else vm.setAppLock(false)
        },
        exportJson = { exportJson.launch("screentime-backup-$date.json") },
        exportCsv = { exportCsv.launch("screentime-csv-$date.zip") },
        importJson = { importJson.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
        openUsageAccess = { Permissions.openUsageAccessSettings(context) },
        openBattery = { Permissions.openBatteryOptimizationSettings(context) },
    )

    Box(Modifier.fillMaxSize()) {
        SettingsContent(s, permissions, busy, actions)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all data?") },
            text = { Text("All sessions, unlocks and notification counts will be removed from this device. Settings are kept.") },
            confirmButton = { TextButton(onClick = { vm.clearAll(); confirmDelete = false }) { Text("DELETE", color = Palette.Bad) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL", color = Palette.TextDim) } },
            containerColor = Palette.Surface,
            shape = ControlShape,
        )
    }

    excludePicker?.let { apps ->
        AppPickerSheet(
            title = "Exclude from rankings",
            apps = apps,
            initial = s.excludedPackages,
            applyLabel = "Save",
            onDismiss = { excludePicker = null },
            onApply = { vm.setExcluded(it); excludePicker = null },
        )
    }
}

@Composable
fun SettingsContent(s: Settings, permissions: PermissionState, busy: Boolean, a: SettingsActions) {
    val rows = PaddingValues(0.dp)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", subtitle = "Version ${BuildConfig.VERSION_NAME}")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel("Tracking", contentPadding = rows) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("Sync interval", fontSize = 14.sp)
                    Text("How often Android's usage log is copied. Longer saves a little battery.", fontSize = 11.sp, color = Palette.TextFaint)
                    Spacer(Modifier.height(10.dp))
                    SegmentedControl(
                        listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 120 to "2 h"),
                        s.syncIntervalMinutes, a.setInterval, Modifier.fillMaxWidth(), compact = true,
                    )
                }
                Divider()
                StatusRow(Icons.Filled.DataUsage, "Usage access", permissions.usageAccess, if (permissions.usageAccess) "granted" else "required", a.openUsageAccess)
                Divider()
                StatusRow(Icons.Filled.BatteryChargingFull, "Battery optimisation", permissions.batteryUnrestricted, if (permissions.batteryUnrestricted) "unrestricted" else "optimised", a.openBattery)
            }

            Panel("Daily goal", subtitle = "Shown on the Tracker, widget and trend chart") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (s.dailyGoalMinutes == 0) "OFF" else Format.duration(s.dailyGoalMinutes * 60_000L), style = NumberStyle.copy(fontSize = 26.sp, color = if (s.dailyGoalMinutes == 0) Palette.TextDim else Palette.Text))
                        Text("Adjust in 15-minute steps", style = MetaStyle)
                    }
                    StepButton(Icons.Filled.Remove, "Decrease goal", enabled = s.dailyGoalMinutes > 0) { a.setGoal(s.dailyGoalMinutes - 15) }
                    Spacer(Modifier.width(8.dp))
                    StepButton(Icons.Filled.Add, "Increase goal") { a.setGoal(s.dailyGoalMinutes + 15) }
                }
            }

            Panel("Data", contentPadding = rows) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("Keep data for", fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    SegmentedControl(
                        listOf(0 to "Forever", 90 to "90 d", 180 to "180 d", 365 to "1 year"),
                        s.retentionDays, a.setRetention, Modifier.fillMaxWidth(), compact = true,
                    )
                }
                Divider()
                NavRow("Excluded apps", "Hidden from rankings (launcher, keyboard…)", icon = Icons.Filled.VisibilityOff, value = "${s.excludedPackages.size}", onClick = a.openExclusions)
                Divider()
                NavRow("Delete all usage data", "Cannot be undone – export first", icon = Icons.Filled.DeleteForever, danger = true, onClick = a.deleteAll)
            }

            Panel("Optional features", contentPadding = rows) {
                ToggleRow(
                    Icons.Filled.Notifications,
                    "Count notifications",
                    if (permissions.notificationAccess) "Per app. Only package and time are stored, never content."
                    else "Needs notification access – tap to grant",
                    s.countNotifications && permissions.notificationAccess,
                    a.toggleNotifications,
                )
                Divider()
                ToggleRow(Icons.Filled.Fingerprint, "App lock", "Require fingerprint, face or PIN to open", s.appLock, a.toggleAppLock)
            }

            Panel("Backup & transfer", subtitle = "Move your history to a new phone", contentPadding = rows) {
                Text(
                    "Export a backup, copy the file to the new device and import it there. Imports merge – nothing already on the device is overwritten.",
                    fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Divider()
                NavRow("Export full backup", "JSON · re-importable", icon = Icons.Filled.FileDownload, enabled = !busy, onClick = a.exportJson)
                Divider()
                NavRow("Export for spreadsheets", "ZIP of CSV files", icon = Icons.Filled.TableChart, enabled = !busy, onClick = a.exportCsv)
                Divider()
                NavRow("Import backup", "Select a .json backup", icon = Icons.Filled.FileUpload, enabled = !busy, onClick = a.importJson)
            }

            Panel("About") {
                Text("Screentime Diagnostics ${BuildConfig.VERSION_NAME}", fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                AboutLine("Privacy", "No internet permission. Data lives in a private on-device database, is excluded from cloud backups and only leaves the phone when you export it.")
                AboutLine("Battery", "No background service. A short job copies Android's own usage log every ${s.syncIntervalMinutes} min.")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutLine(title: String, text: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title.uppercase(), style = LabelStyle)
        Text(text, fontSize = 12.sp, color = Palette.TextDim)
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.OutlinedIconButton(
            onClick = onClick,
            enabled = enabled,
            shape = ControlShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) Palette.Outline else Palette.Grid),
            modifier = Modifier.size(44.dp),
        ) {
            Icon(icon, description, tint = if (enabled) Palette.Text else Palette.TextFaint)
        }
    }
}

@Composable
private fun StatusRow(icon: ImageVector, label: String, ok: Boolean, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        StatusDot(if (ok) Palette.Good else Palette.Warn)
        Spacer(Modifier.width(6.dp))
        Text(value, style = MetaStyle.copy(color = Palette.TextDim, fontSize = 12.sp))
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            Text(hint, fontSize = 11.sp, color = Palette.TextFaint)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Bg,
                checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.TextDim,
                uncheckedTrackColor = Palette.SurfaceHigh,
                uncheckedBorderColor = Palette.Outline,
            ),
        )
    }
}
