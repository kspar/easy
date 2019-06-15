package pages

import PageId
import getElemById
import kotlin.dom.appendText

object CoursesPage : Page {
    override val pageId: PageId
        get() = PageId.COURSES

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses$")

    override fun build() {
        getElemById("container").appendText("courses")
    }

    override fun clear() {
        // Do not clear for testing
    }
}
