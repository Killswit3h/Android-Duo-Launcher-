package com.jake.duolauncher.search

import android.content.Intent
import android.provider.Settings
import java.util.Locale

/**
 * The Settings section of FR-72: "a curated list of Android settings screens".
 *
 * Android publishes no searchable index of system settings — the Settings app's own search is
 * private — so curation is the documented approach (ADR-6 keeps the provider list fixed for the
 * same reason). Each entry is a public `Settings.ACTION_*` (or `Intent.ACTION_*`) constant, a stable
 * id the UI maps to a string resource, and the words users actually type for it.
 *
 * Every action here has existed since well before minSdk 31, but a device is still free not to
 * implement one, so the wiring layer passes an [SettingsProvider.availability] backed by
 * `PackageManager.resolveActivity` and unresolvable rows never appear.
 */
object DuoSettingsDestinations {

    val all: List<SettingsDestination> = listOf(
        SettingsDestination(
            id = "wifi",
            title = "Wi-Fi",
            action = Settings.ACTION_WIFI_SETTINGS,
            keywords = listOf("wifi", "wireless", "network", "internet", "hotspot"),
        ),
        SettingsDestination(
            id = "bluetooth",
            title = "Bluetooth",
            action = Settings.ACTION_BLUETOOTH_SETTINGS,
            keywords = listOf("bluetooth", "pair", "headphones", "earbuds", "audio"),
        ),
        SettingsDestination(
            id = "display",
            title = "Display",
            action = Settings.ACTION_DISPLAY_SETTINGS,
            keywords = listOf("display", "screen", "brightness", "dark", "theme", "refresh", "timeout"),
        ),
        SettingsDestination(
            id = "sound",
            title = "Sound & vibration",
            action = Settings.ACTION_SOUND_SETTINGS,
            keywords = listOf("sound", "volume", "ringtone", "vibration", "silent", "audio"),
        ),
        SettingsDestination(
            id = "battery",
            title = "Battery",
            action = Intent.ACTION_POWER_USAGE_SUMMARY,
            keywords = listOf("battery", "power", "charge", "usage"),
        ),
        SettingsDestination(
            id = "battery-saver",
            title = "Battery saver",
            action = Settings.ACTION_BATTERY_SAVER_SETTINGS,
            keywords = listOf("battery", "saver", "power", "low"),
        ),
        SettingsDestination(
            id = "apps",
            title = "Apps",
            action = Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
            keywords = listOf("apps", "applications", "manage", "uninstall", "permissions"),
        ),
        SettingsDestination(
            id = "default-apps",
            title = "Default apps",
            action = Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            keywords = listOf("default", "apps", "browser", "assistant", "opening links"),
        ),
        SettingsDestination(
            id = "home",
            title = "Default home app",
            action = Settings.ACTION_HOME_SETTINGS,
            keywords = listOf("home", "launcher", "default", "duo"),
        ),
        SettingsDestination(
            id = "storage",
            title = "Storage",
            action = Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            keywords = listOf("storage", "space", "memory", "free", "cleanup"),
        ),
        SettingsDestination(
            id = "accessibility",
            title = "Accessibility",
            action = Settings.ACTION_ACCESSIBILITY_SETTINGS,
            keywords = listOf("accessibility", "talkback", "magnification", "font", "contrast"),
        ),
        SettingsDestination(
            id = "date-time",
            title = "Date & time",
            action = Settings.ACTION_DATE_SETTINGS,
            keywords = listOf("date", "time", "clock", "timezone", "24 hour"),
        ),
        SettingsDestination(
            id = "language",
            title = "Languages",
            action = Settings.ACTION_LOCALE_SETTINGS,
            keywords = listOf("language", "languages", "locale", "region", "translate"),
        ),
        SettingsDestination(
            id = "keyboard",
            title = "Keyboard",
            action = Settings.ACTION_INPUT_METHOD_SETTINGS,
            keywords = listOf("keyboard", "input", "typing", "ime", "autocorrect"),
        ),
        SettingsDestination(
            id = "developer",
            title = "Developer options",
            action = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            keywords = listOf("developer", "options", "debugging", "adb", "usb"),
        ),
        SettingsDestination(
            id = "notification-access",
            title = "Notification access",
            action = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            keywords = listOf("notification", "notifications", "access", "listener", "badges"),
        ),
        SettingsDestination(
            id = "location",
            title = "Location",
            action = Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            keywords = listOf("location", "gps", "maps", "position"),
        ),
        SettingsDestination(
            id = "privacy",
            title = "Privacy",
            action = Settings.ACTION_PRIVACY_SETTINGS,
            keywords = listOf("privacy", "permissions", "microphone", "camera"),
        ),
        SettingsDestination(
            id = "security",
            title = "Security",
            action = Settings.ACTION_SECURITY_SETTINGS,
            keywords = listOf("security", "lock", "screen lock", "password", "pin", "fingerprint"),
        ),
        SettingsDestination(
            id = "airplane-mode",
            title = "Airplane mode",
            action = Settings.ACTION_AIRPLANE_MODE_SETTINGS,
            keywords = listOf("airplane", "flight", "aeroplane", "offline"),
        ),
        SettingsDestination(
            id = "data-usage",
            title = "Data usage",
            action = Settings.ACTION_DATA_USAGE_SETTINGS,
            keywords = listOf("data", "usage", "mobile", "cellular", "roaming"),
        ),
        SettingsDestination(
            id = "nfc",
            title = "NFC",
            action = Settings.ACTION_NFC_SETTINGS,
            keywords = listOf("nfc", "contactless", "tap", "pay"),
        ),
        SettingsDestination(
            id = "vpn",
            title = "VPN",
            action = Settings.ACTION_VPN_SETTINGS,
            keywords = listOf("vpn", "private network", "tunnel"),
        ),
        SettingsDestination(
            id = "cast",
            title = "Cast",
            action = Settings.ACTION_CAST_SETTINGS,
            keywords = listOf("cast", "screen", "mirror", "tv", "chromecast"),
        ),
        SettingsDestination(
            id = "about",
            title = "About phone",
            action = Settings.ACTION_DEVICE_INFO_SETTINGS,
            keywords = listOf("about", "phone", "device", "android version", "build", "serial"),
        ),
    )
}

/**
 * Matches the curated settings list.
 *
 * Both the title and the keywords are matched, which is what makes "wifi" reach "Wi-Fi" (the title
 * folds to `wi-fi`, so the keyword carries it) and "dark" reach Display.
 */
class SettingsProvider(
    destinations: List<SettingsDestination> = DuoSettingsDestinations.all,
    private val availability: (SettingsDestination) -> Boolean = { true },
    locale: Locale = Locale.getDefault(),
) {
    private class Indexed(
        val destination: SettingsDestination,
        val title: IndexedText,
        val keywords: List<IndexedText>,
    )

    private val indexed: List<Indexed> = destinations.map { destination ->
        Indexed(
            destination,
            IndexedText.of(destination.title, locale),
            destination.keywords.map { IndexedText.of(it, locale) },
        )
    }

    private val order: Comparator<SettingResult> = compareByDescending<SettingResult> { it.score }
        .thenBy { it.match.ordinal }
        .thenBy { it.destination.title.length }
        .thenBy { it.destination.id }

    fun results(query: SearchQuery, limit: Int): List<SettingResult> {
        if (limit <= 0 || query.isBlank) return emptyList()
        val matches = ArrayList<SettingResult>(INITIAL_MATCH_CAPACITY)
        for (entry in indexed) {
            val match = bestMatch(
                matchLabel(query, entry.title),
                matchKeywords(query, entry.keywords),
            ) ?: continue
            if (!availability(entry.destination)) continue
            matches += SettingResult(entry.destination, match.kind, match.score)
        }
        if (matches.isEmpty()) return emptyList()
        matches.sortWith(order)
        return if (matches.size > limit) ArrayList(matches.subList(0, limit)) else matches
    }

    private companion object {
        const val INITIAL_MATCH_CAPACITY = 8
    }
}
