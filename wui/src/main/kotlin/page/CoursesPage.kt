package page

import getElemById
import kotlin.dom.appendText

class CoursesPage {
    init {
        render()
    }

    private fun render() {
        getElemById("container").appendText("courses")
    }
}
