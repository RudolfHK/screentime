package io.github.rudolfhk.screentime.ui

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.rudolfhk.screentime.core.PeriodSelection
import io.github.rudolfhk.screentime.core.RangeKind
import io.github.rudolfhk.screentime.ui.appdetail.AppDetailContent
import io.github.rudolfhk.screentime.ui.settings.SettingsActions
import io.github.rudolfhk.screentime.ui.settings.SettingsContent
import io.github.rudolfhk.screentime.ui.stats.StatsContent
import io.github.rudolfhk.screentime.ui.stats.StatsFilter
import io.github.rudolfhk.screentime.ui.stats.StatsSection
import io.github.rudolfhk.screentime.ui.theme.Palette
import io.github.rudolfhk.screentime.ui.theme.ScreenTimeTheme
import io.github.rudolfhk.screentime.ui.tracker.TrackerContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders each screen with sample data to app/build/outputs/roborazzi/ for visual review.
 * The device is extra tall so whole scrolling screens fit into one image.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h2600dp-xhdpi")
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val permissions = PermissionState(usageAccess = true, notificationAccess = true, batteryUnrestricted = false)
    private val week = PeriodSelection(RangeKind.WEEK, SampleData.today)

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            ScreenTimeTheme {
                // Mirrors the app's Scaffold, which provides the background and content colour.
                Surface(Modifier.fillMaxSize(), color = Palette.Bg, contentColor = Palette.Text) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun tracker() = shot("01_tracker") {
        TrackerContent(
            settings = SampleData.settings,
            today = SampleData.bundle(PeriodSelection(RangeKind.DAY, SampleData.today)),
            permissions = permissions,
            busy = false,
            now = System.currentTimeMillis(),
            onToggle = {}, onSync = {}, onGrantUsage = {}, onOpenBattery = {}, onOpenApp = {}, onSeeAllApps = {},
        )
    }

    @Test fun trackerFirstRun() = shot("02_tracker_first_run") {
        TrackerContent(
            settings = SampleData.settings.copy(trackingEnabled = false, hasEverTracked = false, dailyGoalMinutes = 0),
            today = null,
            permissions = PermissionState(),
            busy = false,
            now = System.currentTimeMillis(),
            onToggle = {}, onSync = {}, onGrantUsage = {}, onOpenBattery = {}, onOpenApp = {}, onSeeAllApps = {},
        )
    }

    private fun stats(name: String, section: StatsSection, selection: PeriodSelection = week) = shot(name) {
        StatsContent(
            filter = StatsFilter(selection),
            bundle = SampleData.bundle(selection),
            allApps = SampleData.appInfo.values.toList(),
            onSelectPeriod = {}, onSetPackages = {}, onSetCategory = {}, onOpenApp = {},
            initialSection = section,
        )
    }

    @Test fun statsOverview() = stats("03_stats_overview", StatsSection.OVERVIEW)
    @Test fun statsApps() = stats("04_stats_apps", StatsSection.APPS)
    @Test fun statsPatterns() = stats("05_stats_patterns", StatsSection.PATTERNS, PeriodSelection(RangeKind.MONTH, SampleData.today))
    @Test fun statsDays() = stats("06_stats_days", StatsSection.DAYS, PeriodSelection(RangeKind.MONTH, SampleData.today))

    @Test fun appDetail() = shot("07_app_detail") {
        val pkg = "com.instagram.android"
        AppDetailContent(pkg, week, SampleData.bundle(week, setOf(pkg)), onSelect = {}, onBack = {})
    }

    @Test fun settings() = shot("08_settings") {
        SettingsContent(SampleData.settings.copy(excludedPackages = setOf("a", "b", "c")), permissions, busy = false, a = SettingsActions())
    }
}
