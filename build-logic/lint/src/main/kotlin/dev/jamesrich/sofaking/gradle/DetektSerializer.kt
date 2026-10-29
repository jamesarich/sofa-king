package dev.jamesrich.sofaking.gradle

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/** Holds no state: its `maxParallelUsages` of 1 keeps detekt tasks of a build from running at the same time. */
abstract class DetektSerializer : BuildService<BuildServiceParameters.None>
