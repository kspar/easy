package core.util

import org.joda.time.DateTime

fun DateTime?.notNullAndInPast() = this != null && this.isBeforeNow
