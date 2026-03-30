package changelogai.core.llm.cancellation

import com.intellij.openapi.progress.ProgressIndicator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

internal class ProgressIndicatorWrapperTest {

    @Test
    fun `should return true when progress indicator is canceled`() {
        val indicator = mock<ProgressIndicator>()
        whenever(indicator.isCanceled).thenReturn(true)

        assertTrue(ProgressIndicatorWrapper(indicator).isCanceled())
    }

    @Test
    fun `should return false when progress indicator is not canceled`() {
        val indicator = mock<ProgressIndicator>()
        whenever(indicator.isCanceled).thenReturn(false)

        assertFalse(ProgressIndicatorWrapper(indicator).isCanceled())
    }

    @Test
    fun `checkCanceled delegates to indicator`() {
        val indicator = mock<ProgressIndicator>()
        // no exception thrown — should complete normally
        ProgressIndicatorWrapper(indicator).checkCanceled()
    }
}
