package dev.jamesrich.sofaking.data

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/**
 * A fresh directory name under the system's temporary directory, as an absolute path: DataStore refuses relative
 * paths, and on Linux native without TMPDIR the temporary directory comes back empty, which is the working directory.
 */
internal fun testDirectory(): Path {
    val base = SystemTemporaryDirectory.takeIf { it.toString().isNotEmpty() } ?: Path(".")
    return Path(SystemFileSystem.resolve(base), "sofaking-test-${Random.nextLong().toULong()}")
}
