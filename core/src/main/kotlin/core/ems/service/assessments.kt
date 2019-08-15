package core.ems.service

import core.db.AutomaticAssessment
import core.db.TeacherAssessment
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.dao.IdTable
import org.jetbrains.exposed.sql.*


/**
 * Return the valid grade for this submission or null if it's ungraded.
 * This grade can come from either a teacher or automatic assessment.
 */
fun selectLatestGradeForSubmission(submissionId: Long): Int? {
    val teacherGrade = TeacherAssessment
            .slice(TeacherAssessment.submission,
                    TeacherAssessment.createdAt,
                    TeacherAssessment.grade)
            .select { TeacherAssessment.submission eq submissionId }
            .orderBy(TeacherAssessment.createdAt to false)
            .limit(1)
            .map { it[TeacherAssessment.grade] }
            .firstOrNull()

    if (teacherGrade != null)
        return teacherGrade

    val autoGrade = AutomaticAssessment
            .slice(AutomaticAssessment.submission,
                    AutomaticAssessment.createdAt,
                    AutomaticAssessment.grade)
            .select { AutomaticAssessment.submission eq submissionId }
            .orderBy(AutomaticAssessment.createdAt to false)
            .limit(1)
            .map { it[AutomaticAssessment.grade] }
            .firstOrNull()

    return autoGrade
}

/**
 * infix method derived from Kotlin inList. It is inList analogue, except this method controls similarity (~ / !~), not equality.
 */
infix fun <T> ExpressionWithColumnType<T>.containsInList(list: Iterable<T>): Op<Boolean> = ContainsListOrNotInListOp(this, list, containsInList = true)

/**
 * Derived from Kotlin inList for IDs, controls similarity (~ / !~), not equality.
 */
@Suppress("UNCHECKED_CAST")
@JvmName("inListIds")
infix fun <T : Comparable<T>> Column<EntityID<T>>.containsInList(list: Iterable<T>): Op<Boolean> {
    val idTable = (columnType as EntityIDColumnType<T>).idColumn.table as IdTable<T>
    return containsInList(list.map { EntityID(it, idTable) })
}

class ContainsListOrNotInListOp<T>(val expr: ExpressionWithColumnType<T>, val list: Iterable<T>, val containsInList: Boolean = true) : Op<Boolean>() {
    override fun toSQL(queryBuilder: QueryBuilder): String = buildString {
        list.iterator().let { i ->
            if (!i.hasNext()) {
                val expr = Op.build { booleanLiteral(!containsInList) eq booleanLiteral(true) }
                append(expr.toSQL(queryBuilder))
            } else {
                val first = i.next()
                if (!i.hasNext()) {
                    append(expr.toSQL(queryBuilder))
                    when {
                        containsInList -> append(" ~ ")
                        else -> append(" !~ ")
                    }
                    append(queryBuilder.registerArgument(expr.columnType, first))
                } else {
                    append(expr.toSQL(queryBuilder))
                    when {
                        containsInList -> append(" IN (")
                        else -> append(" NOT IN (")
                    }

                    queryBuilder.registerArguments(expr.columnType, list).joinTo(this)

                    append(")")
                }
            }
        }
    }
}