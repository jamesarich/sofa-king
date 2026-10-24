package dev.jamesrich.sofaking.data

import dev.jamesrich.sofaking.contract.AdBreakState
import dev.jamesrich.sofaking.contract.ApiError
import dev.jamesrich.sofaking.contract.Bootstrap
import dev.jamesrich.sofaking.contract.Capabilities
import dev.jamesrich.sofaking.contract.Catalog
import dev.jamesrich.sofaking.contract.Channel
import dev.jamesrich.sofaking.contract.ChannelGuide
import dev.jamesrich.sofaking.contract.ClientProfile
import dev.jamesrich.sofaking.contract.CreateRecordingRule
import dev.jamesrich.sofaking.contract.GuideQuery
import dev.jamesrich.sofaking.contract.GuideWindow
import dev.jamesrich.sofaking.contract.Library
import dev.jamesrich.sofaking.contract.LibraryHome
import dev.jamesrich.sofaking.contract.LibraryItem
import dev.jamesrich.sofaking.contract.LibraryItemDetail
import dev.jamesrich.sofaking.contract.LibraryPage
import dev.jamesrich.sofaking.contract.MediaSegment
import dev.jamesrich.sofaking.contract.MediaTrack
import dev.jamesrich.sofaking.contract.OpenPlayback
import dev.jamesrich.sofaking.contract.OpenVodPlayback
import dev.jamesrich.sofaking.contract.PairingClaim
import dev.jamesrich.sofaking.contract.PairingResult
import dev.jamesrich.sofaking.contract.PlayState
import dev.jamesrich.sofaking.contract.PlaybackDescriptor
import dev.jamesrich.sofaking.contract.PlaybackSession
import dev.jamesrich.sofaking.contract.Program
import dev.jamesrich.sofaking.contract.ProgramFacts
import dev.jamesrich.sofaking.contract.Recording
import dev.jamesrich.sofaking.contract.RecordingList
import dev.jamesrich.sofaking.contract.RecordingRule
import dev.jamesrich.sofaking.contract.RecordingRuleList
import dev.jamesrich.sofaking.contract.ScheduleRecording
import dev.jamesrich.sofaking.contract.UpdatePlayState
import dev.jamesrich.sofaking.contract.UpdatePlayback
import dev.jamesrich.sofaking.contract.UpdateRecording
import dev.jamesrich.sofaking.contract.UpdateRecordingRule
import dev.jamesrich.sofaking.contract.UpdateVodPlayback
import dev.jamesrich.sofaking.contract.ViewerPreferences
import dev.jamesrich.sofaking.contract.VodSession
import kotlinx.serialization.json.Json

/** Reads the server's `/api/v1` resources; fields the app does not know are ignored. */
internal val wireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

// The contract's wire types under names that stay clear of the app's own Channel and Program models.
internal typealias CatalogDto = Catalog
internal typealias CatalogChannelDto = Channel
internal typealias GuideQueryDto = GuideQuery
internal typealias GuideWindowDto = GuideWindow
internal typealias ChannelGuideDto = ChannelGuide
internal typealias ProgramDto = Program
internal typealias OpenPlaybackDto = OpenPlayback
internal typealias UpdatePlaybackDto = UpdatePlayback
internal typealias PlaybackDescriptorDto = PlaybackDescriptor
internal typealias PlaybackSessionDto = PlaybackSession
internal typealias AdBreakStateDto = AdBreakState
internal typealias ApiErrorDto = ApiError
internal typealias PairingClaimDto = PairingClaim
internal typealias PairingResultDto = PairingResult
internal typealias ViewerPreferencesDto = ViewerPreferences
internal typealias LibraryDto = Library
internal typealias LibraryItemDto = LibraryItem
internal typealias LibraryPageDto = LibraryPage
internal typealias LibraryHomeDto = LibraryHome
internal typealias LibraryItemDetailDto = LibraryItemDetail
internal typealias MediaTrackDto = MediaTrack
internal typealias MediaSegmentDto = MediaSegment
internal typealias PlayStateDto = PlayState
internal typealias UpdatePlayStateDto = UpdatePlayState
internal typealias OpenVodPlaybackDto = OpenVodPlayback
internal typealias UpdateVodPlaybackDto = UpdateVodPlayback
internal typealias VodSessionDto = VodSession
internal typealias ClientProfileDto = ClientProfile
internal typealias BootstrapDto = Bootstrap
internal typealias CapabilitiesDto = Capabilities
internal typealias RecordingDto = Recording
internal typealias RecordingListDto = RecordingList
internal typealias RecordingRuleDto = RecordingRule
internal typealias RecordingRuleListDto = RecordingRuleList
internal typealias ScheduleRecordingDto = ScheduleRecording
internal typealias CreateRecordingRuleDto = CreateRecordingRule
internal typealias UpdateRecordingDto = UpdateRecording
internal typealias UpdateRecordingRuleDto = UpdateRecordingRule
internal typealias ProgramFactsDto = ProgramFacts
