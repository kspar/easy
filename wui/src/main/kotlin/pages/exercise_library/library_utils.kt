package pages.exercise_library

import components.Crumb
import dao.LibraryDirDAO


enum class DirAccess {
    P, PR, PRA, PRAW, PRAWM
}

private val pathWhitelistRegex = Regex("[^A-Za-z0-9ÕÄÖÜŠŽõäöüšž()._\\- ]")

fun createPathChainSuffix(items: List<String>): String =
    items.joinToString("/", "/") {
        it.replace(pathWhitelistRegex, "").replace(' ', '-')
    }

fun createDirChainCrumbs(parentDirs: List<LibraryDirDAO.ParentsDTO>, thisItemTitle: String): List<Crumb> =
    listOf(Crumb.libraryRoot) +
            parentDirs.map { Crumb(it.name, ExerciseLibraryPage.linkToDir(it.id)) } +
            Crumb(thisItemTitle)
