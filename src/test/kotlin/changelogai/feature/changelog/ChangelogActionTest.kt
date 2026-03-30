package changelogai.feature.changelog

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

internal class ChangelogActionTest {

    @Test
    fun `action can be instantiated`() {
        assertNotNull(ChangelogAction())
    }
}
