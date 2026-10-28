package dev.jamesrich.sofaking.backend.admin

import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdatePanelTest {
    private fun shown(version: String?, revision: String?) =
        createHTML(prettyPrint = false).div { build(version, revision) }

    @Test fun `a snapshot version that already ends in the revision is not followed by it again`() {
        assertEquals("<div>0.1.0-dev.786+fcb47f5</div>", shown("0.1.0-dev.786+fcb47f5", "fcb47f5"))
        assertEquals(
            "<div>0.1.0-dev.786+fcb47f5</div>",
            shown("0.1.0-dev.786+fcb47f5", "fcb47f5a1b2c3d4e5f60718293a4b5c6d7e8f901"),
        )
    }

    @Test fun `a release version shows its revision beside it`() {
        assertEquals("<div>1.4.0 <code>abc1234</code></div>", shown("1.4.0", "abc1234"))
        assertEquals("<div>0.1.0-dev.786+fcb47f5 <code>def5678</code></div>", shown("0.1.0-dev.786+fcb47f5", "def5678"))
    }

    @Test fun `whichever of version and revision is known is shown`() {
        assertEquals("<div><code>abc1234</code></div>", shown("unknown", "abc1234"))
        assertEquals("<div>1.4.0</div>", shown("1.4.0", "unknown"))
    }
}
