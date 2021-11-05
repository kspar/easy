package pages.participants

import BinaryModalComp
import Icons
import components.EzCollComp
import debug
import kotlinx.coroutines.await
import plainDstStr
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import rip.kspar.ezspa.sleep

// buildList is experimental
@ExperimentalStdlibApi
class ParticipantsStudentsListComp(
    private val courseId: String,
    parent: Component?
) : Component(parent) {

    data class StudentProps(
        val firstName: String?, val lastName: String?,
        val email: String, val username: String?, val utUsername: String?,
        val isActive: Boolean, val groups: List<String>
    )

    private lateinit var studentsColl: EzCollComp<StudentProps>

    private lateinit var removeFromCourseModal: BinaryModalComp

    override val children: List<Component>
        get() = listOf(studentsColl, removeFromCourseModal)

    override fun create() = doInPromise {

        val studentProps = listOf(
            StudentProps(
                "Peeter",
                "Paan",
                "peeter.paan@peeter.paan",
                "peeterpaan",
                "peeter_paan123",
                true,
                listOf("Rühm AK")
            ),
            StudentProps(
                null,
                null,
                "jaan.jaanusk12345678@jaan.ee",
                null,
                null,
                false,
                listOf("Rühm AK", "Rühm BC", "Rühm POW")
            ),
            StudentProps(
                "E",
                "A",
                "abc",
                "abc",
                "abc",
                true,
                listOf("Rühm BC")
            ),
            StudentProps(
                "X",
                "X",
                "abc",
                "abc",
                "abc",
                true,
                listOf("Rühm AA")
            ),
        )

        val items = studentProps.map { p ->
            EzCollComp.Item(
                p,
                EzCollComp.ItemTypeIcon(if (p.isActive) Icons.user else Icons.pending),
                if (p.isActive) "${p.firstName} ${p.lastName}" else "(Kutse ootel)",
                if (p.isActive) EzCollComp.TitleStatus.NORMAL else EzCollComp.TitleStatus.INACTIVE,
                topAttr = EzCollComp.ListAttr(
                    "Rühmad",
                    p.groups.map { EzCollComp.ListAttrItem(it) }.toMutableList(),
                    Icons.groups,
                    onClick = ::changeGroups
                ),
                bottomAttrs = buildList<EzCollComp.Attr<StudentProps>> {
                    add(EzCollComp.SimpleAttr("Email", p.email, Icons.email))
                    p.username?.let { add(EzCollComp.SimpleAttr("Kasutajanimi", p.username, Icons.user)) }
                    p.utUsername?.let { add(EzCollComp.SimpleAttr("UT kasutajanimi", p.utUsername, Icons.utUser)) }
                },
                isSelectable = true,
                actions = listOf(
                    EzCollComp.Action(Icons.groups, "Rühmad...", ::changeGroups),
                    EzCollComp.Action(Icons.removeParticipant, "Eemalda kursuselt", ::removeFromCourse),
                ),
            )
        }

        val allGroups: List<String> = studentProps.flatMap { it.groups }.distinct().sorted()

        studentsColl = EzCollComp(
            items,
            EzCollComp.Strings("õpilane", "õpilast"),
            listOf(
                EzCollComp.MassAction(Icons.removeParticipant, "Eemalda kursuselt", ::removeFromCourse)
            ),
            listOf(
                EzCollComp.FilterGroup(
                    "Staatus", listOf(
                        EzCollComp.Filter("Aktiivne", { it.props.isActive }),
                        EzCollComp.Filter("Ootel", { !it.props.isActive }),
                    )
                ),
                EzCollComp.FilterGroup(
                    "Rühm",
                    allGroups.map { g ->
                        EzCollComp.Filter(g, { it.props.groups.contains(g) })
                    }
                ),
            ),
            listOf(
                EzCollComp.Sorter("Rühma ja nime järgi",
                    compareBy<EzCollComp.Item<StudentProps>> { it.props.groups.getOrNull(0) }
                        .thenBy { it.props.groups.getOrNull(1) }
                        .thenBy { it.props.groups.getOrNull(2) }
                        .thenBy { it.props.groups.getOrNull(3) }
                        .thenBy { it.props.groups.getOrNull(4) }
                        .thenBy { it.props.lastName?.lowercase() ?: it.props.email.lowercase() }
                        .thenBy { it.props.firstName?.lowercase() }),
                EzCollComp.Sorter("Nime järgi",
                    compareBy<EzCollComp.Item<StudentProps>> {
                        it.props.lastName?.lowercase() ?: it.props.email.lowercase()
                    }
                        .thenBy { it.props.firstName?.lowercase() })
            ),
            parent = this
        )

        removeFromCourseModal = BinaryModalComp("Eemalda", "Tühista", "Eemaldan...", parent = this)
    }


    override fun render() = plainDstStr(studentsColl.dstId, removeFromCourseModal.dstId)

    override fun postRender() {
        super.postRender()
    }

    override fun renderLoading(): String {
        return super.renderLoading()
    }

    override fun postChildrenBuilt() {
    }

    private var groupIdx = 1
    private suspend fun changeGroups(item: EzCollComp.Item<StudentProps>): EzCollComp.Result {
        val groupAttr = item.topAttr.unsafeCast<EzCollComp.ListAttr<StudentProps, String>>()

        groupAttr.items.add(EzCollComp.ListAttrItem("Rühm ${groupIdx++}"))

        return EzCollComp.ResultModified(listOf(item))
    }

    private suspend fun removeFromCourse(item: EzCollComp.Item<StudentProps>): EzCollComp.Result =
        removeFromCourse(listOf(item))

    private suspend fun removeFromCourse(items: List<EzCollComp.Item<StudentProps>>): EzCollComp.Result {
        debug { "Removing ${items.map { it.title }}" }

        removeFromCourseModal.setContent("Eemalda ${items.size} õpilast?")
        removeFromCourseModal.setPrimaryAction {
            sleep(2000).await()
            true
        }

        debug { "opening modal" }
        val removeConfirmed = removeFromCourseModal.openWithClosePromise().await()
        debug { "modal await complete" }
        debug { "ret = $removeConfirmed" }

        return if (removeConfirmed)
            EzCollComp.ResultModified<StudentProps>(emptyList())
        else
            EzCollComp.ResultUnmodified
    }
}

