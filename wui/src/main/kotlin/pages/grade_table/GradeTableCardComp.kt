package pages.grade_table

import Key
import LocalStore
import components.chips.FilterChipComponent
import components.chips.FilterChipSetComp
import components.chips.FilterToggleChipComp
import components.form.SelectComp
import dao.CoursesTeacherDAO
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import parseToOrCatch
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template
import translation.Str

class GradeTableCardComp(
    private val courseId: String,
    private val courseTitle: String,
    parent: Component?
) : Component(parent) {

    @Serializable
    data class Settings(val showSubNumbers: Boolean, val truncateTitles: Boolean)

    private var groupSelectComp: SelectComp? = null
    private lateinit var subNumberChip: FilterToggleChipComp
    private lateinit var chipSetComp: FilterChipSetComp
    private lateinit var tableComp: GradeTableTableComp


    override val children: List<Component>
        get() = listOfNotNull(groupSelectComp, chipSetComp, tableComp)

    override fun create() = doInPromise {
        val groups = CoursesTeacherDAO.getGroups(courseId).await()

        val preselectedGroupId = LocalStore.get(Key.TEACHER_SELECTED_GROUP)?.let {
            if (groups.map { it.id }.contains(it)) it else null
        }

        if (groups.isNotEmpty()) {
            val options = buildList {
                add(SelectComp.Option("Kõik õpilased", ""))
                groups.forEach {
                    add(SelectComp.Option(it.name, it.id, it.id == preselectedGroupId))
                }
            }
            groupSelectComp = SelectComp(Str.accountGroup, options, onOptionChange = ::handleGroupChange, parent = this)
        }

        val settings = LocalStore.get(Key.GRADE_TABLE_SHOW_SUB_NUMBERS)?.parseToOrCatch(Settings.serializer())
            ?: Settings(showSubNumbers = false, truncateTitles = false)

        // TODO: groups into chip as well
        subNumberChip = FilterToggleChipComp(
            FilterChipComponent.Filter("Näita esituste arvu", selected = settings.showSubNumbers),
            onChange = {
                tableComp.setSettings(subNumbers = it)
            }
        )

        chipSetComp = FilterChipSetComp(listOf(subNumberChip))

        tableComp = GradeTableTableComp(
            courseId,
            groupId = preselectedGroupId,
            showSubNumbers = settings.showSubNumbers,
            truncateExerciseTitles = settings.truncateTitles,
            parent = this,
        )
    }

    override fun render() = template(
        """
            <h2>{{title}}</h2>
            <ez-flex style='margin-top: 3rem;'>
                $groupSelectComp
                $chipSetComp
            </ez-flex>
            $tableComp
        """.trimIndent(),
        "title" to courseTitle,
    )

    private suspend fun handleGroupChange(newGroupId: String?) {
        LocalStore.set(Key.TEACHER_SELECTED_GROUP, newGroupId)
        tableComp.setGroup(newGroupId)
    }
}