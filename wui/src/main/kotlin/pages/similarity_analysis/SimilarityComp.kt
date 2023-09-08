package pages.about

import CONTENT_CONTAINER_ID
import Icons
import cache.BasicCourseInfo
import components.form.ButtonComp
import components.text.AttrsComp
import dao.CourseExercisesTeacherDAO
import dao.ExerciseDAO
import kotlinx.coroutines.await
import pages.Title
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.doInPromise
import template
import translation.Str


class SimilarityComp(
    private val courseId: String,
    private val courseExerciseId: String,
    private val exerciseId: String,
) : Component(null, CONTENT_CONTAINER_ID) {

    private lateinit var courseTitle: String
    private lateinit var attrs: AttrsComp
    private lateinit var btn: ButtonComp
    private lateinit var results: SimilarityResultsComp

    override val children: List<Component>
        get() = listOf(attrs, btn, results)


    override fun create() = doInPromise {
        courseTitle = BasicCourseInfo.get(courseId).await().effectiveTitle
        val exerciseTitle =
            CourseExercisesTeacherDAO.getCourseExerciseDetails(courseId, courseExerciseId).await().effectiveTitle

        Title.update {
            it.pageTitle = Str.similarityAnalysis
            it.parentPageTitle = exerciseTitle
        }

        attrs = AttrsComp(
            mapOf(
                Str.exerciseTitle to exerciseTitle
            ),
            this
        )
        btn = ButtonComp(
            ButtonComp.Type.PRIMARY, Str.findSimilarities, Icons.compareSimilarity, clickedLabel = Str.searching,
            onClick = {
                // TODO: submission number, grade, (feedback?) - either map from this service
                //  or make similarity return and show those
                val submissionIds = CourseExercisesTeacherDAO.getLatestSubmissions(courseId, courseExerciseId).await()
                    .students.map { it.submission_id }.filterNotNull()
                val result = ExerciseDAO.checkSimilarity(exerciseId, listOf(courseId), submissionIds).await()
                results.setData(result)
            }, parent = this
        )
        results = SimilarityResultsComp(this)
    }

    override fun render() = template(
        """
            <div class="title-wrap no-crumb" style='margin-bottom: 2rem;'>
                <h2 class="">{{title}}</h2>
            </div>
            $attrs
            <ez-flex style='margin-top: 2rem;'>$btn</ez-flex>
            $results
        """.trimIndent(),
        "title" to courseTitle,
    )
}

