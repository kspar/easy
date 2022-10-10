package com.example.demo


abstract class PyASTElement {
    abstract fun generatePyString(): String
}

abstract class PyASTPrimitive : PyASTElement()


class PyFunctionCall(val name: String, val namedArgs: Map<String, PyASTPrimitive?>) : PyASTPrimitive() {
    override fun generatePyString(): String {
        val argsString = namedArgs.map {
            "${it.key.trim()}=${it.value?.generatePyString()}"
        }.joinToString(", ")

        return "$name($argsString)\n"
    }
}

class PyExecuteTest(val testName: String, val namedArgs: Map<String, PyASTPrimitive?>) : PyASTPrimitive() {
    override fun generatePyString(): String {
        return PyFunctionCall("execute_test", namedArgs + Pair("testName", PyStr(testName))).generatePyString()
    }
}

class PyStandardOutputChecks(val standardOutputChecks: List<StandardOutputCheck>?) : PyASTPrimitive() {
    override fun generatePyString(): String {
        val value: String? = standardOutputChecks?.map {
            PyFunctionCall(
                "StandardOutputChecks", mapOf(
                    "stringCheckType" to PyStr(it.stringCheckType.toString()),
                    "expectedOutput" to PyStr(it.expectedOutput),
                    "considerElementsOrder" to PyBool(it.considerElementsOrder)
                )
            )
        }?.let { PyList(it) }?.generatePyString()

        if (value.isNullOrEmpty()) {
            return ""
        }

        return value
    }
}

class PyStandardOutputChecksLong(val standardOutputChecksLong: List<StandardOutputCheckLong>?) : PyASTPrimitive() {
    override fun generatePyString(): String {
        val value: String? = standardOutputChecksLong?.map {
            PyFunctionCall(
                "StandardOutputChecks", mapOf(
                    "stringCheckType" to PyStr(it.stringCheckType.toString()),
                    "expectedOutput" to PyStr(it.expectedOutput),
                )
            )
        }?.let { PyList(it) }?.generatePyString()

        if (value.isNullOrEmpty()) {
            return ""
        }

        return value
    }
}

class PyOutputTests(val outputFileChecks: List<OutputFileCheck>?) : PyASTPrimitive() {
    override fun generatePyString(): String {
        val value: String? = outputFileChecks?.map {
            PyFunctionCall(
                "OutputFileChecks", mapOf(
                    "fileName" to PyStr(it.fileName),
                    "stringCheckType" to PyStr(it.stringCheckType.toString()),
                    "expectedOutput" to PyStr(it.expectedOutput),
                    "considerElementsOrder" to PyBool(it.considerElementsOrder)
                )
            )
        }?.let { PyList(it) }?.generatePyString()

        if (value.isNullOrEmpty()) {
            return ""
        }

        return value
    }
}

// TODO: .generatePyString() võiks toimuda hiljem, mitte igas harus

class PyInt(val value: Long) : PyASTPrimitive() {
    // Long.toString() is always a valid Python integer primitive?
    override fun generatePyString() = value.toString()
}

class PyStr(val value: String?) : PyASTPrimitive() {
    override fun generatePyString() = "'''${value?.replace("'''", "\\'''")?.trim()}'''"
}

class PyFloat(val value: Double) : PyASTPrimitive() {
    override fun generatePyString() = TODO("Not yet implemented")
}

class PyList(val values: List<PyASTPrimitive>) : PyASTPrimitive() {
    override fun generatePyString() = values.joinToString(", ", "[", "]") {
        it.generatePyString()
    }
}

class PyTuple(val values: List<PyASTPrimitive>) : PyASTPrimitive() {
    override fun generatePyString() = values.joinToString(", ", "(", ")") {
        it.generatePyString()
    }
}

class PyPair(val value1: PyASTPrimitive, val value2: PyASTPrimitive) : PyASTPrimitive() {
    override fun generatePyString() = "(${value1.generatePyString()}, ${value2.generatePyString()})"
}

class PyBool(val value: Boolean?) : PyASTPrimitive() {
    override fun generatePyString() = value.toString().replaceFirstChar { it.uppercase() }
}


fun main() {
    println(
        PyFunctionCall(
            "func",
            mapOf(
                "arg1" to PyInt(2),
                "bla" to PyStr("yo'''y\"\"\"y\"o'y\"o"),
                "asd" to PyList(listOf(PyList(listOf(PyInt(1), PyInt(2))), PyInt(5))),
                "a" to PyBool(false),
                "b" to PyPair(PyInt(1), PyInt(2)),
                "b" to PyPair(PyStr("hello"), PyInt(2))
            )
        ).generatePyString()
    )
}