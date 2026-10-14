package dev.jamesrich.sofaking.ui

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.network.ktor3.KtorNetworkFetcherFactory
import dev.jamesrich.sofaking.data.mediaHttpClient

/**
 * Coil's loader for channel logos and program images, over the API's connection pool. Coil's own memory cache,
 * disk cache (not on the web) and decoding to the slot's size do the rest, so logos are on screen at a cold
 * start without the network and a guide full of them holds no full-size bitmaps.
 */
internal fun sofaKingImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(KtorNetworkFetcherFactory(httpClient = { mediaHttpClient })) }
        .build()
