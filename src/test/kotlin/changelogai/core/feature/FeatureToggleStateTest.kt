package changelogai.core.feature

import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.swing.JPanel

internal class FeatureToggleStateTest {

    private lateinit var state: FeatureToggleState

    private val testFeature = object : Feature {
        override val id = "test_feature"
        override val name = "Test"
        override val description = "desc"
        override val enabledByDefault = true
        override fun isAvailable(project: Project) = true
        override fun createTab(project: Project) = JPanel()
    }

    @BeforeEach
    fun setup() {
        state = FeatureToggleState()
    }

    @Test
    fun `isEnabled returns default when not overridden`() {
        assertEquals(true, state.isEnabled(testFeature))
    }

    @Test
    fun `setEnabled overrides default`() {
        state.setEnabled(testFeature.id, false)
        assertEquals(false, state.isEnabled(testFeature))
    }

    @Test
    fun `loadState copies toggles`() {
        val other = FeatureToggleState()
        other.toggles[testFeature.id] = false

        state.loadState(other)

        assertEquals(false, state.isEnabled(testFeature))
    }

    @Test
    fun `getState returns self`() {
        assertEquals(state, state.getState())
    }
}
