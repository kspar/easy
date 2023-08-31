package pages.grade_table

import Key
import LocalStore
import components.form.SelectComp
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.*
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.IdGenerator
import rip.kspar.ezspa.doInPromise
import template

class GradeTableCardComp(
    private val courseId: String,
    private val courseTitle: String,
    parent: Component?
) : Component(parent) {

    @Serializable
    data class Groups(
        val groups: List<Group>,
        val self_is_restricted: Boolean,
    )

    @Serializable
    data class Group(
        val id: String,
        val name: String
    )

    private var groupSelectComp: SelectComp? = null
    private lateinit var tableComp: GradeTableTableComp

    // Static ID for table since it's recreated
    private val tableDstId = IdGenerator.nextId()

    override val children: List<Component>
        get() = listOfNotNull(groupSelectComp, tableComp)

    override fun create() = doInPromise {
        val groups = fetchEms(
            "/courses/$courseId/groups", ReqMethod.GET, successChecker = { http200 },
            errorHandler = ErrorHandlers.noCourseAccessMsg
        ).await()
            .parseTo(Groups.serializer()).await()
            .groups.sortedBy { it.name }

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
            groupSelectComp = SelectComp("Rühm", options, onOptionChange = ::handleGroupChange, parent = this)
        }

        tableComp = GradeTableTableComp(
            courseId,
            groupId = preselectedGroupId,
            parent = this,
            dstId = tableDstId
        )
    }

    override fun render() = template(
        """
            <div class="title-wrap">
            <h2 class="title">{{title}}</h2>
        </div>
        <div class="card">
            <div class="card-content">
                <ez-dst style='display: flex;' id="{{groupSelectDstId}}"></ez-dst>
                <ez-dst id="{{tableDstId}}"></ez-dst>
            </div>
        </div>
        """.trimIndent(),
        "title" to courseTitle,
        "groupSelectDstId" to groupSelectComp?.dstId,
        "tableDstId" to tableDstId
    )

    private suspend fun handleGroupChange(newGroupId: String?) {
        LocalStore.set(Key.TEACHER_SELECTED_GROUP, newGroupId)
        tableComp = GradeTableTableComp(
            courseId,
            groupId = newGroupId,
            parent = this,
            dstId = tableDstId
        )
        tableComp.createAndBuild().await()
    }
}