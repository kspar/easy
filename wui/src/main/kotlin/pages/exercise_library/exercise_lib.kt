package pages.exercise_library

import DateSerializer
import Icons
import components.BreadcrumbsComp
import components.Crumb
import components.EzCollComp
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import pages.exercise.ExercisePage
import pages.exercise.GraderType
import plainDstStr
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import toEstonianString
import kotlin.js.Date


class ExerciseLibRootComp(
    dstId: String
) : Component(null, dstId) {


    @Serializable
    data class Lib(
        val current_dir: Dir?,
        val child_dirs: List<Dir>,
        val child_exercises: List<Exercise>,
    )

    @Serializable
    data class Dir(
        val id: String,
        val name: String,
        val effective_access: DirAccess,
        @Serializable(with = DateSerializer::class)
        val created_at: Date,
        @Serializable(with = DateSerializer::class)
        val modified_at: Date,
    )

    @Serializable
    data class Exercise(
        val exercise_id: String,
        val dir_id: String,
        val title: String,
        val effective_access: DirAccess,
        val grader_type: GraderType,
        val courses_count: Int,
        @Serializable(with = DateSerializer::class)
        val created_at: Date,
        @Serializable(with = DateSerializer::class)
        val modified_at: Date,
    )

    enum class DirAccess {
        P, PR, PRA, PRAW, PRAWM
    }

    data class ExerciseProps(
        val id: String,
        val title: String,
        val graderType: GraderType,
        val coursesCount: Int,
        val access: DirAccess,
        val modifiedAt: Date,
    )

    private lateinit var breadcrumbs: BreadcrumbsComp
    private lateinit var ezcoll: EzCollComp<ExerciseProps>

    override val children: List<Component>
        get() = listOf(breadcrumbs, ezcoll)

    override fun create() = doInPromise {
        breadcrumbs = BreadcrumbsComp(listOf(Crumb("Ülesandekogu")), this)

        val libResp = fetchEms("/lib/dirs/root", ReqMethod.GET, successChecker = { http200 }).await()
            .parseTo(Lib.serializer()).await()

        val props = libResp.child_exercises.map {
            ExerciseProps(
                it.exercise_id,
                it.title,
                it.grader_type,
                it.courses_count,
                it.effective_access,
                it.modified_at,
            )
        }

        val items = props.map { p ->
            EzCollComp.Item(
                p,
                if (p.graderType == GraderType.AUTO)
                    EzCollComp.ItemTypeIcon(Icons.robot)
                else
                    EzCollComp.ItemTypeIcon(Icons.user),
                p.title,
                titleLink = ExercisePage.link(p.id),
                topAttr = EzCollComp.SimpleAttr("Viimati muudetud", p.modifiedAt.toEstonianString(), Icons.pending),
                bottomAttrs = listOf(
                    EzCollComp.SimpleAttr("Kasutusel", "${p.coursesCount} kursusel", Icons.courses),
                    EzCollComp.SimpleAttr(
                        "Mul on lubatud",
                        p.access.toString(),
                        Icons.exercisePermissions,
                        translateDirAccess(p.access)
                    ),
                    EzCollComp.SimpleAttr("ID", p.id, Icons.id)
                ),
            )
        }

        ezcoll = EzCollComp(
            items, EzCollComp.Strings("ülesanne", "ülesannet"),
            filterGroups = listOf(
                EzCollComp.FilterGroup(
                    "Hindamine", listOf(
                        EzCollComp.Filter("Automaatne") { it.props.graderType == GraderType.AUTO },
                        EzCollComp.Filter("Käsitsi") { it.props.graderType == GraderType.TEACHER },
                    )
                )
            ),
            sorters = listOf(
                EzCollComp.Sorter("Nime järgi", compareBy { it.props.title }),
                // TODO: Date to comparable
//                EzCollComp.Sorter("Muutmisaja järgi", compareBy { it.props.modifiedAt }),
                EzCollComp.Sorter("ID järgi", compareBy { it.props.id.toInt() }),
                EzCollComp.Sorter("Populaarsuse järgi", compareBy<EzCollComp.Item<ExerciseProps>> { it.props.coursesCount }.reversed()),
            ),
            parent = this
        )
    }

    override fun render() = plainDstStr(breadcrumbs.dstId, ezcoll.dstId)

    override fun renderLoading() = "Loading..."

    private fun translateDirAccess(access: DirAccess): String {
        return when (access) {
            DirAccess.P -> "vaadata"
            DirAccess.PR -> "vaadata"
            DirAccess.PRA -> "vaadata ja lisada"
            DirAccess.PRAW -> "vaadata ja muuta"
            DirAccess.PRAWM -> "kõike teha"
        }
    }
}