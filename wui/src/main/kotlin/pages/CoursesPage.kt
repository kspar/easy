package pages

import getElemById
import kotlin.dom.appendText

object CoursesPage : Page {

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses$")

    override fun build() {
        getElemById("container").appendText("courses")
    }
}
