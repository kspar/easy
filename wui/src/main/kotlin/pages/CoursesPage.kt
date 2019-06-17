package pages

import PageId
import debug
import getElemById
import kotlin.dom.appendText

object CoursesPage : Page {
    override val pageId: PageId
        get() = PageId.COURSES

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses$")

    override fun build() {
        debug { "CoursesPage.build" }
        getElemById("container").appendText("courses")
    }

}
