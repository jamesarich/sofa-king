package dev.jamesrich.sofaking.ui

import coil3.request.ImageRequest

/**
 * Asks for a picture the CPU can draw into another bitmap and read back: on Android a software bitmap, since a
 * hardware one cannot be drawn into the bitmap the ambient color is read from. Other platforms decode that way already.
 */
internal expect fun ImageRequest.Builder.cpuReadable(): ImageRequest.Builder
