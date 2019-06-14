package pages

import getElemById
import kotlin.dom.appendText

object ExercisesPage {
    fun render() {
        getElemById("container").appendText("exercises")
    }
}
