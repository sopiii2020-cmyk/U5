package dev.voltguard.battery.data.prefs

import android.content.Context
import dev.voltguard.battery.engine.HomeProfileMode
import dev.voltguard.battery.engine.TileSelection

class OptimizerPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("voltguard_prefs", Context.MODE_PRIVATE)

    fun getTileSelection(): TileSelection {
        val raw = prefs.getString(KEY_TILE_SELECTION, TileSelection.AUTO.name)
        return runCatching { TileSelection.valueOf(raw ?: TileSelection.AUTO.name) }
            .getOrDefault(TileSelection.AUTO)
    }

    fun setTileSelection(mode: TileSelection) {
        prefs.edit().putString(KEY_TILE_SELECTION, mode.name).apply()
    }

    fun getHomeProfile(): HomeProfileMode {
        val raw = prefs.getString(KEY_HOME_PROFILE, HomeProfileMode.AUTOMATIC.name)
        return runCatching { HomeProfileMode.valueOf(raw ?: HomeProfileMode.AUTOMATIC.name) }
            .getOrDefault(HomeProfileMode.AUTOMATIC)
    }

    fun setHomeProfile(profile: HomeProfileMode) {
        val (tile, agg) = when (profile) {
            HomeProfileMode.AUTOMATIC -> TileSelection.AUTO to 45
            HomeProfileMode.BALANCE -> TileSelection.AUTO to 58
            HomeProfileMode.ULTRA_SAFE -> TileSelection.LONGEVITY to 12
            HomeProfileMode.INTELLIGENT -> TileSelection.AUTO to 42
        }
        prefs.edit()
            .putString(KEY_HOME_PROFILE, profile.name)
            .putString(KEY_TILE_SELECTION, tile.name)
            .putInt(KEY_AGGRESSIVENESS, agg)
            .apply()
    }

    fun getAggressiveness(): Int = prefs.getInt(KEY_AGGRESSIVENESS, 45)
    fun setAggressiveness(value: Int) {
        prefs.edit().putInt(KEY_AGGRESSIVENESS, value.coerceIn(0, 100)).apply()
    }

    fun getTypicalUnplugHour(): Int = prefs.getInt(KEY_UNPLUG_HOUR, 7)
    fun getDivergenceCount(): Int = prefs.getInt(KEY_DIVERGENCE, 0)

    fun isNudge80Enabled(): Boolean = prefs.getBoolean(KEY_NUDGE_80, false)
    fun setNudge80Enabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NUDGE_80, enabled).apply()
    }

    fun isPersistentNotificationEnabled(): Boolean = prefs.getBoolean(KEY_PERSISTENT_NOTIF, false)
    fun setPersistentNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERSISTENT_NOTIF, enabled).apply()
    }

    fun isAutoStartEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_START, true)
    fun setAutoStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    fun isRootBridgeOptInEnabled(): Boolean = prefs.getBoolean(KEY_ROOT_OPT_IN, false)
    fun setRootBridgeOptInEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ROOT_OPT_IN, enabled).apply()
    }

    fun getBlockedPackages(): Set<String> =
        prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    fun toggleBlockedPackage(packageName: String): Boolean {
        val updated = getBlockedPackages().toMutableSet()
        val nowBlocked = if (updated.contains(packageName)) {
            updated.remove(packageName)
            false
        } else {
            updated.add(packageName)
            true
        }
        prefs.edit().putStringSet(KEY_BLOCKED_PACKAGES, updated).apply()
        return nowBlocked
    }

    fun isInactivityShieldEnabled(): Boolean = prefs.getBoolean(KEY_INACTIVITY_SHIELD, false)
    fun setInactivityShieldEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INACTIVITY_SHIELD, enabled).apply()
    }

    fun savePreviousBrightness(brightness0To255: Int) {
        if (!prefs.contains(KEY_PREV_BRIGHTNESS)) {
            prefs.edit().putInt(KEY_PREV_BRIGHTNESS, brightness0To255.coerceIn(1, 255)).apply()
        }
    }

    fun consumePreviousBrightness(): Int? {
        if (!prefs.contains(KEY_PREV_BRIGHTNESS)) return null
        val prev = prefs.getInt(KEY_PREV_BRIGHTNESS, 128)
        prefs.edit().remove(KEY_PREV_BRIGHTNESS).apply()
        return prev
    }

    fun saveQuickSettingsSnapshot(wifi: Boolean, mobileData: Boolean, bluetooth: Boolean, sync: Boolean, location: Boolean) {
        prefs.edit()
            .putBoolean(KEY_QS_SNAPSHOT_SAVED, true)
            .putBoolean(KEY_QS_WIFI, wifi)
            .putBoolean(KEY_QS_DATA, mobileData)
            .putBoolean(KEY_QS_BT, bluetooth)
            .putBoolean(KEY_QS_SYNC, sync)
            .putBoolean(KEY_QS_LOC, location)
            .apply()
    }

    fun consumeQuickSettingsSnapshot(): QuickSettingsSnapshot? {
        if (!prefs.getBoolean(KEY_QS_SNAPSHOT_SAVED, false)) return null
        val snap = QuickSettingsSnapshot(
            wifi = prefs.getBoolean(KEY_QS_WIFI, false),
            mobileData = prefs.getBoolean(KEY_QS_DATA, false),
            bluetooth = prefs.getBoolean(KEY_QS_BT, false),
            sync = prefs.getBoolean(KEY_QS_SYNC, false),
            location = prefs.getBoolean(KEY_QS_LOC, false)
        )
        prefs.edit().putBoolean(KEY_QS_SNAPSHOT_SAVED, false).apply()
        return snap
    }

    companion object {
        private const val KEY_TILE_SELECTION = "tile_selection"
        private const val KEY_HOME_PROFILE = "home_profile"
        private const val KEY_AGGRESSIVENESS = "aggressiveness"
        private const val KEY_UNPLUG_HOUR = "typical_unplug_hour"
        private const val KEY_DIVERGENCE = "prediction_divergence"
        private const val KEY_NUDGE_80 = "nudge_80"
        private const val KEY_PERSISTENT_NOTIF = "persistent_notification_card"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_ROOT_OPT_IN = "root_opt_in"
        private const val KEY_BLOCKED_PACKAGES = "blocked_packages_guard"
        private const val KEY_INACTIVITY_SHIELD = "inactivity_lifespan_shield"
        private const val KEY_PREV_BRIGHTNESS = "shield_prev_brightness"
        private const val KEY_QS_SNAPSHOT_SAVED = "qs_snapshot_saved"
        private const val KEY_QS_WIFI = "qs_wifi"
        private const val KEY_QS_DATA = "qs_data"
        private const val KEY_QS_BT = "qs_bt"
        private const val KEY_QS_SYNC = "qs_sync"
        private const val KEY_QS_LOC = "qs_loc"
    }
}

data class QuickSettingsSnapshot(
    val wifi: Boolean,
    val mobileData: Boolean,
    val bluetooth: Boolean,
    val sync: Boolean,
    val location: Boolean
)
