package page

import getElemById
import kotlin.dom.appendText

class ExercisesPage {
    init {
        render()
    }

    private fun render() {
        getElemById("container").appendText("exercises")
    }
}
