package changelogai.core.feature

import com.intellij.openapi.project.Project
import javax.swing.Icon
import javax.swing.JPanel

/**
 * Представляет собой функциональный компонент приложения.
 *
 * @property id Уникальный идентификатор фичи.
 * @property name Название фичи.
 * @property description Описание фичи.
 */
interface Feature {
    /**
     * Уникальный идентификатор фичи.
     */
    val id: String

    /**
     * Название фичи.
     */
    val name: String

    /**
     * Краткое описание фичи.
     */
    val description: String

    /**
     * Фича включена по умолчанию?
     * По умолчанию возвращается true.
     */
    val enabledByDefault: Boolean get() = true

    /** Иконка для бокового навбара. Если null — фича не отображается в навбаре. */
    val icon: Icon? get() = null

    /**
     * Проверяет доступность фичи для данного проекта.
     *
     * @param project Текущий проект IntelliJ IDEA.
     * @return True, если фича доступна; false иначе.
     */
    fun isAvailable(project: Project): Boolean

    /**
     * Создает панель управления фичей для отображения в пользовательском интерфейсе.
     *
     * @param project Текущий проект IntelliJ IDEA.
     * @return Панель управления фичей.
     */
    fun createTab(project: Project): JPanel
}