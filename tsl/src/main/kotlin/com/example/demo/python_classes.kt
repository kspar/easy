package com.example.demo

import tsl.common.model.*

class PyExecuteTest(val test: Test, val testName: String, val namedArgs: Map<String, PyASTPrimitive?>) :
    PyASTPrimitive() {
    override fun generatePyString(): String =
        PyFunctionCall(
            "execute_test",
            namedArgs +
                    Pair("type", PyStr(testName)) +
                    Pair("points_weight", PyFloat(test.pointsWeight)) +
                    Pair("id", PyInt(test.id)) +
                    Pair("name", if (test.name != null) PyStr(test.name) else PyStr(test.getDefaultName())) +
                    Pair("inputs", PyStr(test.inputs)) +
                    Pair("passed_next", PyInt(test.passedNext)) +
                    Pair("failed_next", PyInt(test.failedNext)) +
                    Pair("visible_to_user", PyBool(test.visibleToUser))
        ).generatePyString()
}


class PyGenericChecks(val genericChecks: List<GenericCheck>?) : PyASTPrimitive() {
    constructor(genericCheck: GenericCheck) : this(
        listOf(genericCheck)
    )

    override fun generatePyString(): String {
        if (genericChecks == null) {
            return "[]"
        }
        return PyList(
            genericChecks.map {
                PyDict(
                    mapOf(
                        "check_type" to PyStr(it.checkType.toString()),
                        "output_category" to PyStr(it.outputCategory.toString()),
                        "nothing_else" to PyBool(it.nothingElse),
                        // Always the literal path. The old per-check forceString compared a
                        // List<String>.toString() against an enum name — unconditionally true —
                        // so this spells out what has in fact always happened.
                        "expected_value" to PyList(it.expectedValue.map { PyStr(it) }),
                        "elements_ordered" to PyBool(it.elementsOrdered),
                        "before_message" to PyStr(it.beforeMessage),
                        "passed_message" to PyStr(it.passedMessage),
                        "failed_message" to PyStr(it.failedMessage),
                        "data_category" to PyStr(it.dataCategory.toString()),
                        "ignore_case" to PyBool(it.ignoreCase)
                    )
                )
            }).generatePyString()
    }
}

class PyGenericChecksLong(val genericChecksLong: List<GenericCheckLong>?) : PyASTPrimitive() {
    constructor(genericCheck: GenericCheckLong) : this(
        listOf(genericCheck)
    )

    override fun generatePyString(): String {
        if (genericChecksLong == null) {
            return "[]"
        }
        return PyList(
            genericChecksLong.map {
                PyDict(
                    mapOf(
                        "check_type" to PyStr(it.checkType.toString()),
                        "nothing_else" to PyBool(it.nothingElse),
                        // Always the literal path — see the note on the sibling above.
                        "expected_value" to PyList(it.expectedValue.map { PyStr(it) }),
                        "before_message" to PyStr(it.beforeMessage),
                        "passed_message" to PyStr(it.passedMessage),
                        "failed_message" to PyStr(it.failedMessage),
                        "data_category" to PyStr(it.dataCategory.toString()),
                        "ignore_case" to PyBool(it.ignoreCase)
                    )
                )
            }).generatePyString()
    }
}

class PyOutputTests(val outputFileChecks: List<OutputFileCheck>?) : PyASTPrimitive() {
    override fun generatePyString(): String {
        if (outputFileChecks == null) {
            return "[]"
        }
        return PyList(
            outputFileChecks.map {
                PyDict(
                    mapOf(
                        "file_name" to PyStr(it.fileName),
                        "check_type" to PyStr(it.checkType.toString()),
                        "nothing_else" to PyBool(it.nothingElse),
                        "expected_value" to PyList(it.expectedValue.map { PyStr(it) }),
                        "elements_ordered" to PyBool(it.elementsOrdered),
                        "before_message" to PyStr(it.beforeMessage),
                        "passed_message" to PyStr(it.passedMessage),
                        "failed_message" to PyStr(it.failedMessage),
                        "data_category" to PyStr(it.dataCategory.toString()),
                        "ignore_case" to PyBool(it.ignoreCase)
                    )
                )
            }).generatePyString()
    }
}