package de.j4velin.smarthome.proximityturnon

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore by preferencesDataStore("settings")

data class Settings(
    /**
     * Master switch, toggled from the dashboard or by Home Assistant via broadcast
     * (see [LightSensorService.ACTION_ENABLE]). When off the sensor is not read
     * and no wake lock is held, but the service stays alive to receive the
     * enable broadcast.
     */
    val enabled: Boolean = true,
    /** Whether the service was running when the device shut down, i.e. should be restarted on boot */
    val autoStart: Boolean = false,
    val wakeOnShadow: Boolean = true,
    /** Percentage the light level must drop below the baseline to count as a shadow */
    val shadowDropPercent: Int = 15,
    /** Only wake if the front camera sees a face after a shadow was detected */
    val confirmWithCamera: Boolean = false,
    /** Play a short tone when a shadow is detected, i.e. when the light sensor alone would wake the screen */
    val beepOnShadow: Boolean = true,
) {
    companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val WAKE_ON_SHADOW = booleanPreferencesKey("wake_on_shadow")
        val SHADOW_DROP_PERCENT = intPreferencesKey("shadow_drop_percent")
        val CONFIRM_WITH_CAMERA = booleanPreferencesKey("confirm_with_camera")
        val BEEP_ON_SHADOW = booleanPreferencesKey("beep_on_shadow")

        fun from(prefs: Preferences) = Settings(
            enabled = prefs[ENABLED] ?: true,
            autoStart = prefs[AUTO_START] ?: false,
            wakeOnShadow = prefs[WAKE_ON_SHADOW] ?: true,
            shadowDropPercent = prefs[SHADOW_DROP_PERCENT] ?: 15,
            confirmWithCamera = prefs[CONFIRM_WITH_CAMERA] ?: false,
            beepOnShadow = prefs[BEEP_ON_SHADOW] ?: true,
        )
    }
}

fun Context.settingsFlow(): Flow<Settings> = settingsDataStore.data.map { Settings.from(it) }

suspend fun Context.updateSettings(block: (Settings) -> Settings) {
    settingsDataStore.edit { prefs ->
        val new = block(Settings.from(prefs))
        prefs[Settings.ENABLED] = new.enabled
        prefs[Settings.AUTO_START] = new.autoStart
        prefs[Settings.WAKE_ON_SHADOW] = new.wakeOnShadow
        prefs[Settings.SHADOW_DROP_PERCENT] = new.shadowDropPercent
        prefs[Settings.CONFIRM_WITH_CAMERA] = new.confirmWithCamera
        prefs[Settings.BEEP_ON_SHADOW] = new.beepOnShadow
    }
}
