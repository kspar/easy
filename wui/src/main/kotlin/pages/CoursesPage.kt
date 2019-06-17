package pages

import PageName
import debugFunEnd
import debugFunStart
import getElemById
import spa.Page
import kotlin.dom.appendText

object CoursesPage : Page<Nothing>() {

    override val pageName: PageName
        get() = PageName.COURSES

    override fun pathMatches(path: String): Boolean =
            path.matches("^/courses$")

    override fun build(pageState: Nothing?) {
        val fl = debugFunStart("CoursesPage.build")

        getElemById("container").appendText("courses")

        debugFunEnd(fl)
    }

}
