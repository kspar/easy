package com.example.demo

class PyExecuteTest(val test: Test, val testName: String, val namedArgs: Map<String, PyASTPrimitive?>) :
    PyASTPrimitive() {
    override fun generatePyString(): String =
        PyFunctionCall(
            "execute_test",
            namedArgs +
                    Pair("test_name", PyStr(testName)) +
                    Pair("points", PyFloat(test.points)) +
                    Pair("id", PyInt(test.id)) +
                    Pair("name", PyStr(test.name)) +
                    Pair("inputs", PyStr(test.inputs)) +
                    Pair("passedNext", PyInt(test.passedNext)) +
                    Pair("failedNext", PyInt(test.failedNext)) +
                    Pair("visibleToUser", PyBool(test.visibleToUser))
        ).generatePyString()
}


class PyStandardOutputChecks(val standardOutputChecks: List<StandardOutputCheck>?) : PyASTPrimitive() {
    constructor(standardOutputCheck: StandardOutputCheck) : this(
        listOf(standardOutputCheck)
    )

    override fun generatePyString(): String {
        if (standardOutputChecks == null) {
            return ""
        }
        return PyList(
            standardOutputChecks.map {
                PyFunctionCall(
                    "StandardOutputChecks", mapOf(
                        "string_check_type" to PyStr(it.stringCheckType.toString()),
                        "expected_output" to PyStr(it.expectedOutput),
                        "consider_elements_order" to PyBool(it.considerElementsOrder),
                        "before_message" to PyStr(it.beforeMessage),
                        "passedMessage" to PyStr(it.passedMessage),
                        "failedMessage" to PyStr(it.failedMessage)
                    )
                )
            }).generatePyString()
    }
}

class PyStandardOutputChecksLong(val standardOutputChecksLong: List<StandardOutputCheckLong>?) : PyASTPrimitive() {
    constructor(standardOutputCheck: StandardOutputCheckLong) : this(
        listOf(standardOutputCheck)
    )

    override fun generatePyString(): String {
        if (standardOutputChecksLong == null) {
            return ""
        }
        return PyList(
            standardOutputChecksLong.map {
                PyFunctionCall(
                    "StandardOutputChecks", mapOf(
                        "string_check_type" to PyStr(it.stringCheckType.toString()),
                        "expected_output" to PyStr(it.expectedOutput),
                        "before_message" to PyStr(it.beforeMessage),
                        "passedMessage" to PyStr(it.passedMessage),
                        "failedMessage" to PyStr(it.failedMessage)
                    )
                )
            }).generatePyString()
    }
}

class PyOutputTests(val outputFileChecks: List<OutputFileCheck>?) : PyASTPrimitive() {
    override fun generatePyString(): String {
        if (outputFileChecks == null) {
            return ""
        }
        return PyList(
            outputFileChecks.map {
                PyFunctionCall(
                    "OutputFileChecks", mapOf(
                        "file_name" to PyStr(it.fileName),
                        "string_check_type" to PyStr(it.stringCheckType.toString()),
                        "expected_output" to PyStr(it.expectedOutput),
                        "consider_elements_order" to PyBool(it.considerElementsOrder),
                        "before_message" to PyStr(it.beforeMessage),
                        "passedMessage" to PyStr(it.passedMessage),
                        "failedMessage" to PyStr(it.failedMessage)
                    )
                )
            }).generatePyString()
    }
}