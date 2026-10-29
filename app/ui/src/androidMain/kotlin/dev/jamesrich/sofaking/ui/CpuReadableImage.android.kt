package dev.jamesrich.sofaking.ui

import coil3.request.ImageRequest
import coil3.request.allowHardware

internal actual fun ImageRequest.Builder.cpuReadable(): ImageRequest.Builder = allowHardware(false)
