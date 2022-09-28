package com.example.demo


abstract class PyASTElement {
    abstract fun generatePyString(): String
}

abstract class PyASTPrimitive : PyASTElement()


class PyFunctionCall(val name: String, val namedArgs: Map<String, PyASTPrimitive>) : PyASTElement() {
    override fun generatePyString(): String {
        val argsString = namedArgs.map {
            "${it.key.trim()}=${it.value.generatePyString()}"
        }.joinToString(", ")

        return "$name($argsString)\n"
    }
}

class PyInt(val value: Long) : PyASTPrimitive() {
    // Long.toString() is always a valid Python integer primitive?
    override fun generatePyString() = value.toString()
}

class PyStr(val value: String) : PyASTPrimitive() {
    override fun generatePyString() = "'''${value.replace("'''", "\\'''").trim()}'''"
}

class PyFloat(val value: Double) : PyASTPrimitive() {
    override fun generatePyString() = TODO("Not yet implemented")
}


fun main() {
    println(
        PyFunctionCall(
            "func",
            mapOf(
                "arg1" to PyInt(2),
                "bla" to PyStr("yo'''y\"\"\"y\"o'y\"o")
            )
        ).generatePyString()
    )
}