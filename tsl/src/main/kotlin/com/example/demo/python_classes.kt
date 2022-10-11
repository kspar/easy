package com.example.demo

class PyExecuteTest(val testName: String, val namedArgs: Map<String, PyASTPrimitive?>) : PyASTPrimitive() {
    override fun generatePyString(): String =
        PyFunctionCall("execute_test", namedArgs + Pair("test_name", PyStr(testName))).generatePyString()
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
                    "standard_output_checks", mapOf(
                        "string_check_type" to PyStr(it.stringCheckType.toString()),
                        "expected_output" to PyStr(it.expectedOutput),
                        "consider_elements_order" to PyBool(it.considerElementsOrder)
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
                    "standard_output_checks", mapOf(
                        "string_check_type" to PyStr(it.stringCheckType.toString()),
                        "expected_output" to PyStr(it.expectedOutput),
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
                "output_file_checks", mapOf(
                    "file_name" to PyStr(it.fileName),
                    "string_check_type" to PyStr(it.stringCheckType.toString()),
                    "expected_output" to PyStr(it.expectedOutput),
                    "consider_elements_order" to PyBool(it.considerElementsOrder)
                )
            )
        }).generatePyString()
    }
}