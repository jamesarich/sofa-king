package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.Catalog
import dev.jamesrich.sofaking.contract.Channel
import dev.jamesrich.sofaking.contract.ChannelGuide
import dev.jamesrich.sofaking.contract.GuideQuery
import dev.jamesrich.sofaking.contract.GuideWindow
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.PlaybackDescriptor
import dev.jamesrich.sofaking.contract.PlaybackSession
import dev.jamesrich.sofaking.contract.Programme
import dev.jamesrich.sofaking.contract.UpdatePlayback
import kotlinx.serialization.json.Json

/** Reads the server's `/api/v1` resources; fields the app does not know are ignored. */
internal val wireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

// The contract's wire types under names that stay clear of the app's own Channel and Programme models.
internal typealias CatalogDto = Catalog
internal typealias CatalogChannelDto = Channel
internal typealias GuideQueryDto = GuideQuery
internal typealias GuideWindowDto = GuideWindow
internal typealias ChannelGuideDto = ChannelGuide
internal typealias ProgrammeDto = Programme
internal typealias OpenPlaybackDto = OpenPlayback
internal typealias UpdatePlaybackDto = UpdatePlayback
internal typealias PlaybackDescriptorDto = PlaybackDescriptor
internal typealias PlaybackSessionDto = PlaybackSession
internal typealias ApiErrorDto = ApiError
