package changelogai.core.feature

import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import javax.swing.JPanel

private class SimpleFeature : Feature {
    override val id = "test-id"
    override val name = "Test feature"
    override val description = "This is a test feature."
    override val enabledByDefault = true
    override fun isAvailable(project: Project) = true
    override fun createTab(project: Project) = JPanel()
}

internal class FeatureTest {

    private val feature = SimpleFeature()

    @Test
    fun `should return correct ID`() = assertEquals("test-id", feature.id)

    @Test
    fun `should return correct name`() = assertEquals("Test feature", feature.name)

    @Test
    fun `should return correct description`() = assertEquals("This is a test feature.", feature.description)

    @Test
    fun `should be available by default`() {
        assertTrue(feature.isAvailable(mock<Project>()))
    }

    @Test
    fun `should create tab panel`() {
        assertNotNull(feature.createTab(mock<Project>()))
    }
}
