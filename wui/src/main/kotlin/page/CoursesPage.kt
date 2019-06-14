package page

import getElemById
import kotlin.dom.appendText

object CoursesPage {
    fun render() {
        getElemById("container").appendText("courses")
    }
}
