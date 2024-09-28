package pages.about

import CONTENT_CONTAINER_ID
import Icons
import cache.BasicCourseInfo
import components.form.ButtonComp
import components.form.SelectComp
import dao.CourseExercisesTeacherDAO
import dao.CoursesTeacherDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import pages.Title
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import storage.getSavedGroupId
import storage.saveGroup
import template
import translation.Str


class SimilarityComp(
    private val courseId: String,
    private val exerciseId: String?,
) : Component(null, CONTENT_CONTAINER_ID) {

    private lateinit var selectExercise: SelectComp
    private var selectGroup: SelectComp? = null
    private lateinit var btn: ButtonComp
    private lateinit var results: SimilarityResultsComp

    override val children: List<Component>
        get() = listOfNotNull(selectExercise, selectGroup, btn, results)


    override fun create() = doInPromise {
        val courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle

        Title.update {
            it.pageTitle = Str.similarityAnalysis
            it.parentPageTitle = courseTitle
        }

        val exercises = CourseExercisesTeacherDAO.getCourseExercises(courseId).await()

        selectExercise = SelectComp(
            Str.exercise,
            exercises.map {
                SelectComp.Option(it.effectiveTitle, it.exercise_id, it.exercise_id == exerciseId)
            },
            parent = this
        )

        val groups = CoursesTeacherDAO.getGroups(courseId).await()

        val preselectedGroupId = getSavedGroupId(courseId)?.let {
            if (groups.map { it.id }.contains(it)) it else null
        }

        if (groups.isNotEmpty()) {
            val options = buildList {
                add(SelectComp.Option(Str.allStudents, ""))
                groups.forEach {
                    add(SelectComp.Option(it.name, it.id, it.id == preselectedGroupId))
                }
            }
            selectGroup = SelectComp(
                Str.accountGroup, options,
                onOptionChange = { saveGroup(courseId, it) },
                parent = this
            )
        }

        btn = ButtonComp(
            ButtonComp.Type.FILLED, Str.findSimilarities, Icons.compareSimilarity,
            clickedLabel = Str.searching,
            onClick = {
                // TODO: submission number, grade, (feedback?) - either map from this service
                //  or make similarity return and show those
                val exId = selectExercise.getValue()
                val groupId = selectGroup?.getValue()
                if (exId != null) {
                    val ceId = exercises.first { it.exercise_id == exId }.course_exercise_id
                    val submissionIds = CourseExercisesTeacherDAO.getLatestSubmissions(courseId, ceId, groupId).await()
                        .latest_submissions.map { it.submission?.id }.filterNotNull()
                    val result = ExerciseDAO.checkSimilarity(exId, listOf(courseId), submissionIds).await()
                    results.setData(result)
                }
            }, parent = this
        )

        results = SimilarityResultsComp(this)
    }

    override fun render() = template(
        """
            <div class="title-wrap no-crumb" style='margin-bottom: 2rem;'>
                <h2 class="">{{title}}</h2>
            </div>
            
            <p>{{explanation}}</p>
           
            <ez-similarity-select-exercise id='${selectExercise.dstId}' style='margin-top: 2rem;'>
            </ez-similarity-select-exercise>
            
            ${selectGroup ?: ""}
            
            <ez-flex style='margin-top: 2rem; margin-bottom: 2rem;'>$btn</ez-flex>
            $results
        """.trimIndent(),
        "title" to Str.similarityAnalysis,
        "explanation" to Str.similaritiesHelpText,
    )
}

