package dev.jamesrich.sofaking.backend.iptvorg

import dev.jamesrich.sofaking.backend.EnvironmentReader
import dev.jamesrich.sofaking.backend.settings.Settings

/** The iptv-org switches and region the environment sets; a region that is not country codes stops the start. */
internal fun iptvOrgSettings(env: EnvironmentReader) = IptvOrgSettings(
    env.choice(Settings.IPTV_ORG) == IptvOrgSettings.ON,
    env.choice(Settings.IPTV_ORG_STREAMS) == IptvOrgSettings.ON,
    env.text(Settings.IPTV_ORG_REGION)?.let {
        requireNotNull(IptvOrgSettings.cleanRegion(it)) { Settings.IPTV_ORG_REGION.failure }
    }.orEmpty(),
)
