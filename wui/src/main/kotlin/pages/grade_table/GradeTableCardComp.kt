package pages.grade_table

import HumanStringComparator
import components.SorterComp
import components.chips.FilterChipComponent
import components.chips.FilterChipSetComp
import components.chips.FilterDropdownChipComp
import components.chips.FilterToggleChipComp
import dao.CoursesTeacherDAO
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import pages.grade_table.GradeTableCardComp.Sorter.FINISHED_COUNT
import pages.grade_table.GradeTableCardComp.Sorter.NAME
import parseToOrCatch
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import storage.Key
import storage.LocalStore
import storage.getSavedGroupId
import storage.saveGroup
import stringify
import template
import translation.Str

class GradeTableCardComp(
    private val courseId: String,
    private val courseTitle: String,
    parent: Component?
) : Component(parent) {

    @Serializable
    data class Settings(
        val showSubNumbers: Boolean, val sorterId: String, val sorterReversed: Boolean,
    )

    enum class Sorter(
        val comparator: Comparator<GradeTableTableComp.StudentRow>,
        val id: String
    ) {
        NAME(
            compareBy<GradeTableTableComp.StudentRow, String?>(HumanStringComparator) { it.groups.getOrNull(0) }
                .thenBy(HumanStringComparator) { it.groups.getOrNull(1) }
                .thenBy(HumanStringComparator) { it.groups.getOrNull(2) }
                .thenBy(HumanStringComparator) { it.groups.getOrNull(3) }
                .thenBy(HumanStringComparator) { it.groups.getOrNull(4) }
                .thenBy { it.lastName.lowercase() }
                .thenBy { it.firstName.lowercase() },
            "SORTER_NAME",
        ),
        FINISHED_COUNT(
            compareByDescending { it.finishedCount },
            "SORTER_FINISHED",
        )
    }


    private var groupSelectComp: FilterDropdownChipComp? = null
    private lateinit var subNumberChip: FilterToggleChipComp
    private lateinit var chipSetComp: FilterChipSetComp
    private lateinit var sorter: SorterComp

    private lateinit var tableComp: GradeTableTableComp


    override val children: List<Component>
        get() = listOfNotNull(groupSelectComp, chipSetComp, sorter, tableComp)

    override fun create() = doInPromise {
        val groups = CoursesTeacherDAO.getGroups(courseId).await()

        val preselectedGroupId = getSavedGroupId(courseId)?.let {
            if (groups.map { it.id }.contains(it)) it else null
        }

        groupSelectComp = if (groups.isNotEmpty()) {
            FilterDropdownChipComp(
                Str.accountGroup,
                groups.map {
                    FilterChipComponent.Filter(it.name, selected = it.id == preselectedGroupId, id = it.id)
                },
                onChange = {
                    handleGroupChange(it?.id)
                }
            )
        } else null

        val settings = LocalStore.get(Key.GRADE_TABLE_SHOW_SUB_NUMBERS)?.parseToOrCatch(Settings.serializer())
            ?: Settings(showSubNumbers = false, sorterId = Sorter.values().first().id, sorterReversed = false)

        val activeSorter = Sorter.values().first { it.id == settings.sorterId }

        subNumberChip = FilterToggleChipComp(
            FilterChipComponent.Filter(Str.showSubmissionNumber, selected = settings.showSubNumbers),
            onChange = {
                tableComp.setShowSubNumbers(it)
                saveSettings()
            }
        )

        chipSetComp = FilterChipSetComp(listOfNotNull(groupSelectComp, subNumberChip))

        val compSorters = listOf(
            SorterComp.Sorter(Str.sortByName, NAME.id),
            SorterComp.Sorter(Str.sortBySuccess, FINISHED_COUNT.id),
        )

        sorter = SorterComp(
            compSorters,
            onChanged = { sorter, reversed ->
                val activeComparator = Sorter.values().first { it.id == sorter.id }.comparator
                val comparator = if (reversed) activeComparator.reversed() else activeComparator
                tableComp.setComparator(comparator)
                saveSettings()
            },
            initialSorter = compSorters.first { it.id == activeSorter.id },
            initialReversed = settings.sorterReversed,
        )


        tableComp = GradeTableTableComp(
            courseId,
            groupId = preselectedGroupId,
            showSubNumbers = settings.showSubNumbers,
            comparator = Sorter.values().first { it.id == settings.sorterId }.comparator.let {
                if (settings.sorterReversed) it.reversed() else it
            },
            parent = this,
        )
    }

    override fun render() = template(
        """
            <h2>{{title}}</h2>
            <ez-flex style='justify-content: space-between; margin: 3rem 0;'>
                $chipSetComp
                $sorter
            </ez-flex>
            $tableComp
        """.trimIndent(),
        "title" to courseTitle,
    )

    private suspend fun handleGroupChange(newGroupId: String?) {
        saveGroup(courseId, newGroupId)
        tableComp.setGroup(newGroupId)
    }

    private fun saveSettings() {
        LocalStore.set(
            Key.GRADE_TABLE_SHOW_SUB_NUMBERS,
            Settings.serializer().stringify(
                Settings(
                    showSubNumbers = subNumberChip.filter.selected,
                    sorterId = sorter.activeSorter.id,
                    sorterReversed = sorter.isReversed,
                )
            )
        )
    }
}