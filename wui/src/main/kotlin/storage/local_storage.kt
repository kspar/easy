package storage

import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set


enum class Key {
    LANGUAGE,
    ACTIVE_ROLE,
    TEACHER_SELECTED_GROUP,

    LIBRARY_USER_CONF,
    TEACHER_COURSE_EXERCISE_SUBMISSIONS_USER_CONF,
    STUDENT_COURSE_EXERCISES_USER_CONF,
    TEACHER_COURSE_EXERCISES_USER_CONF,
    COURSE_PARTICIPANTS_USER_CONF,

    TEACHER_COURSE_EXERCISE_PANEL_EXPAND_STATE,
    STUDENT_COURSE_EXERCISE_PANEL_EXPAND_STATE,

    TEACHER_SEND_COMMENT_NOTIFICATION,
    TEACHER_SEND_GRADE_NOTIFICATION,

    GRADE_TABLE_SHOW_SUB_NUMBERS,

    EXERCISE_EMBED_OPTIONS_VIEW,
}

object LocalStore {
    const val TEACHER_SELECTED_GROUP_NONE_ID = "NONE"
    private const val KEY_PREFIX = "EZ_"

    fun set(key: Key, value: String?) {
        if (value == null)
            localStorage.removeItem(KEY_PREFIX + key.name)
        else
            localStorage[KEY_PREFIX + key.name] = value
    }

    fun get(key: Key) = localStorage[KEY_PREFIX + key.name]
}