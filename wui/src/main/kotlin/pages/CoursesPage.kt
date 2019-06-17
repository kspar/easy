package pages

import PageId
import debugFunEnd
import debugFunStart
import getElemById
import kotlin.dom.appendText

object CoursesPage : Page<Nothing>() {

    override val pageId: PageId
        get() = PageId.COURSES

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses$")

    override fun build(pageState: Nothing?) {
        val fl = debugFunStart("CoursesPage.build")

        getElemById("container").appendText("courses")

        debugFunEnd(fl)
    }

}
