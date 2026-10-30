package dev.jamesrich.sofaking.backend.iptvorg

import kotlinx.serialization.Serializable
import java.time.ZoneId

/** The three iptv-org settings as the server holds them: metadata, streams, and the region's text. */
data class IptvOrgSettings(val metadata: Boolean = false, val streams: Boolean = false, val region: String = "") {
    /** Whether anything needs the downloaded files. */
    val enabled: Boolean get() = metadata || streams

    companion object {
        const val ON = "on"
        const val OFF = "off"
        private const val MAX_COUNTRIES = 20

        /**
         * [raw] as upper-case ISO 3166-1 alpha-2 codes joined by commas; empty stays empty, and null when an entry is
         * not a two-letter code or more than 20 are listed.
         */
        fun cleanRegion(raw: String): String? {
            val codes = raw.split(',', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
            if (codes.size > MAX_COUNTRIES || codes.any { it.length != 2 || !it.all(Char::isLetter) }) return null
            return codes.joinToString(",")
        }
    }
}

/** The countries streams are imported for, and where that choice came from. */
@Serializable
data class IptvOrgRegion(val countries: List<String>, val source: String, val detail: String)

/**
 * The region in force: the saved or environment value when one is set, else the country of the server's [zone] from
 * iptv-org's time zone list, else `US`. [timezones] is empty until the list has been downloaded.
 */
fun effectiveRegion(setting: String, zone: ZoneId, timezones: Map<String, String>): IptvOrgRegion {
    val set = IptvOrgSettings.cleanRegion(setting).orEmpty()
    if (set.isNotEmpty()) return IptvOrgRegion(set.split(','), "setting", "set in Settings")
    val country = timezones[zone.id]
    return if (country != null) {
        IptvOrgRegion(listOf(country), "time-zone", "from the server's time zone ${zone.id}")
    } else {
        IptvOrgRegion(
            listOf("US"),
            "default",
            "US, because the time zone ${zone.id} has no country in the iptv-org list",
        )
    }
}
