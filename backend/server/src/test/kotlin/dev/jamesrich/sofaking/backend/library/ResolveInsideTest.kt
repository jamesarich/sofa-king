package dev.jamesrich.sofaking.backend.library

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResolveInsideTest {
    private val root = Path.of("/media/tv")

    @Test fun `a relative path resolves under the folder and is normalized`() {
        assertEquals(Path.of("/media/tv/show/ep.mkv"), root.resolveInside("show/./x/../ep.mkv"))
        assertEquals(root, root.resolveInside(""))
    }

    @Test fun `a path leading out of the folder is refused`() {
        assertNull(root.resolveInside("../secret.mkv"))
        assertNull(root.resolveInside("show/../../secret.mkv"))
        assertNull(root.resolveInside("/etc/passwd"))
    }

    @Test fun `a sibling folder sharing the name prefix is refused`() {
        assertNull(root.resolveInside("../tv-private/ep.mkv"))
    }
}
