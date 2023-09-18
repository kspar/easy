package pages.about

import EzDate
import components.code_editor.CodeDiffEditorComp
import components.text.AttrsComp
import dao.ExerciseDAO
import kotlinx.coroutines.await
import libheaders.Materialize
import rip.kspar.ezspa.Component
import rip.kspar.ezspa.getElemById
import rip.kspar.ezspa.getElemBySelectorOrNull
import template
import translation.Str

class SimilarityResultsComp(
    parent: Component,
) : Component(parent) {

    private var submissions: Map<String, ExerciseDAO.SimilarSubmission> = emptyMap()

    data class Item(val score: ExerciseDAO.SimilarityScore, val attrs: AttrsComp, val editor: CodeDiffEditorComp)

    private var items = emptyList<Item>()


    override val children: List<Component>
        get() = items.flatMap { listOf(it.attrs, it.editor) }

    override fun render() = template(
        """
            {{^empty}}
                <h4 style='margin-top: 4rem;'>{{title}}</h2>
                <ul class="ez-collapsible collapsible" style='margin-top: 1rem;'>
                {{#results}}
                    <li>
                        <div class="collapsible-header">
                            <span>
                                <span style='margin-right: 1rem;'>{{name1}} — {{name2}}</span> | 
                                <span style='margin-left: 1rem; font-weight: 500;'>{{score1}}% · {{score2}}%</span>
                            </span>
                        </div>
                        <div class="collapsible-body">
                            {{{attrs}}}    
                            <span style='display: flex;'>
                                <ez-inline-flex style='flex-grow: 117; margin-left: 3rem; margin-bottom: 1rem; flex-direction: column;'>
                                    <span style='font-weight: 500;'>{{name1}}</span>
                                    {{time1}}
                                </ez-inline-flex>
                                <ez-inline-flex style='flex-grow: 100; margin-left: 3rem; margin-bottom: 1rem; flex-direction: column;'>
                                    <span style='font-weight: 500;'>{{name2}}</span>
                                    {{time2}}
                                </ez-inline-flex>
                            </span>
                            {{{editor}}}
                        </div>
                    </li>
                {{/results}}
            {{/empty}}
        </ul>
        """.trimIndent(),
        "title" to Str.topSimilarPairs,
        "empty" to items.isEmpty(),
        "results" to items.map { item ->
            val sub1 = submissions.getValue(item.score.sub_1)
            val sub2 = submissions.getValue(item.score.sub_2)
            mapOf(
                "name1" to "${sub1.given_name} ${sub1.family_name}",
                "name2" to "${sub2.given_name} ${sub2.family_name}",
                "time1" to sub1.created_at.toHumanString(EzDate.Format.FULL),
                "time2" to sub2.created_at.toHumanString(EzDate.Format.FULL),
                "score1" to item.score.score_a.toString(),
                "score2" to item.score.score_b.toString(),
                "attrs" to item.attrs.toString(),
                "editor" to item.editor.toString(),
            )
        }
    )

    override fun postRender() {
        getElemById(dstId).getElemBySelectorOrNull(".ez-collapsible")?.let {
            Materialize.Collapsible.init(it)
        }
    }

    suspend fun setData(data: ExerciseDAO.Similarity) {
        this.submissions = data.submissions.associateBy { it.id }

        items = data.scores.map { score ->
            val sub1 = submissions.getValue(score.sub_1)
            val sub2 = submissions.getValue(score.sub_2)

            Item(
                score,
                AttrsComp(
                    mapOf(
                        Str.diceSimilarity to "${score.score_a}%",
                        Str.levenshteinSimilarity to "${score.score_b}%",
                    ), this
                ),
                CodeDiffEditorComp(
                    CodeDiffEditorComp.File("lahendus.py", sub1.solution),
                    CodeDiffEditorComp.File("lahendus.py", sub2.solution),
                    parent = this
                )
            )
        }

        createAndBuild().await()
    }
}