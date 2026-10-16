package io.github.rudolfhk.screentime.tracking

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import io.github.rudolfhk.screentime.core.AppCategories
import io.github.rudolfhk.screentime.data.AppInfoEntity

/** Resolves app labels, categories and icons through PackageManager, with an in-memory icon cache. */
class AppInfoResolver(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val icons = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** Label and category of an installed app, or null if it isn't installed (or not visible). */
    fun resolve(packageName: String, now: Long): AppInfoEntity? = try {
        val ai = pm.getApplicationInfo(packageName, 0)
        AppInfoEntity(packageName, pm.getApplicationLabel(ai).toString(), ai.category, now)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun fallback(packageName: String, now: Long) = AppInfoEntity(
        packageName,
        packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() },
        AppCategories.UNDEFINED,
        now,
    )

    fun icon(packageName: String, sizePx: Int = 96): ImageBitmap? {
        icons.get(packageName)?.let { return it.asImageBitmap() }
        if (packageName in missing) return null
        return try {
            val bmp = pm.getApplicationIcon(packageName).toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            icons.put(packageName, bmp)
            bmp.asImageBitmap()
        } catch (e: PackageManager.NameNotFoundException) {
            missing += packageName
            null
        }
    }

    /** Apps that would otherwise distort the ranking: launchers, keyboard, system UI, this app. */
    fun defaultExclusions(): Set<String> {
        val out = mutableSetOf(context.packageName, "com.android.systemui")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY).forEach { out += it.activityInfo.packageName }
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
            ?.let { out += it }
        return out
    }

    /** Launchable apps installed on the device (for the exclusion picker). */
    fun launchablePackages(): Set<String> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet()
    }
}
