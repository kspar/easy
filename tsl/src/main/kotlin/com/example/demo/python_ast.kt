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

        return "$name($argsString)"
    }
}

class PyDict(val namedArgs: Map<String, PyASTPrimitive?>) : PyASTPrimitive() {
    override fun generatePyString(): String {
        val argsString = namedArgs.map {
            "${quoteKey(it.key.trim())}:${it.value?.generatePyString()}"
        }.joinToString(", ")

        return "{$argsString}"
    }

    /**
     * Keys are quoted here rather than by the caller. They used to arrive pre-quoted as `"'name'"`,
     * which worked only for as long as everyone remembered — `param_value_checks` did not, and
     * emitted `{param_number: ...}`, a bare name that raises NameError the moment the generated
     * script runs. Encoding belongs in the encoder.
     */
    private fun quoteKey(key: String) = "'" + key.replace("\\", "\\\\").replace("'", "\\'") + "'"
}

// TODO: .generatePyString() võiks toimuda hiljem, mitte igas harus

class PyInt(val value: Long?) : PyASTPrimitive() {
    // Long.toString() is always a valid Python integer primitive?
    override fun generatePyString(): String {
        if (value == null) {
            return "None"
        }
        return value.toString()
    }
}

class PyStr(val value: String?) : PyASTPrimitive() {
    override fun generatePyString(): String {
        if (value == null) {
            return "None"
        }
        // Always a literal, with no raw-source escape hatch (EZ-1810): these are the fields that
        // carry teacher-typed text — names, messages, expected outputs — and a leading `"` emitted
        // verbatim was a SyntaxError at grading time, or worse, a quiet comparison against a
        // different string. Same bug family closeableInTripleQuotes closes.
        return "'''${closeableInTripleQuotes(value.replace("\n", "\\n").replace("'''", "\\'''").trim())}'''"
    }
}

/**
 * A value emitted verbatim as Python source — function arguments, return values: the fields whose
 * help text itself teaches Python syntax (`nt sõne "abc"`). A separate type rather than a boolean
 * on [PyStr], so a call site says *raw source* where it used to say `false` — the flag spelling is
 * how the EZ-1810 injection family got planted on the literal path in the first place.
 */
class PyRawSource(val value: String?) : PyASTPrimitive() {
    override fun generatePyString(): String = value ?: "None"
}

/**
 * Make [body] safe to sit between `'''` and `'''`, **without changing what it means**.
 *
 * Two ways the old version produced a literal Python could not close, both of them ordinary things
 * for a teacher to type and both of them a `SyntaxError` at grading time rather than at authoring
 * time:
 *
 * 1. **Ends with a quote.** `'''it's'''` is four consecutive quotes; the literal closes after three
 *    and the fourth is stray. Escaping that last one is the whole fix.
 * 2. **Ends with an odd number of backslashes.** `'''C:\'''` escapes the first closing quote, so the
 *    literal runs on to the end of the file. Note this case was never *representable* — the old
 *    output was not a mangled version of the value, it was not a value at all — so completing the
 *    pair adds no meaning that was previously there.
 *
 * Deliberately nothing else. Backslashes inside the text keep being Python escapes, because live
 * specs depend on that: 18 of the 720 exercises in the migration corpus store `\n` and rely on the
 * generated literal turning it into a newline. Re-encoding them "properly" was measured and would
 * have turned those into the two characters backslash and n, in the middle of a student's feedback.
 */
private fun closeableInTripleQuotes(body: String): String {
    // The quote first: escaping it appends a backslash *before* a quote, never at the end, so the
    // trailing-backslash count below is unaffected by having run it.
    val quoteSafe = if (body.endsWith("'") && !body.endsWith("\\'")) body.dropLast(1) + "\\'" else body

    val trailingBackslashes = quoteSafe.length - quoteSafe.trimEnd('\\').length
    return if (trailingBackslashes % 2 == 1) "$quoteSafe\\" else quoteSafe
}

class PyFloat(val value: Double) : PyASTPrimitive() {
    override fun generatePyString() = value.toString()
}

class PyList(val values: List<PyASTPrimitive>) : PyASTPrimitive() {
    override fun generatePyString() = values.joinToString(", ", "[", "]") {
        it.generatePyString()
    }
}

open class PyTuple(val values: List<PyASTPrimitive>) : PyASTPrimitive() {
    override fun generatePyString() = values.joinToString(", ", "(", ")") {
        it.generatePyString()
    }
}

class PyPair(value1: PyASTPrimitive, value2: PyASTPrimitive) : PyTuple(listOf(value1, value2))

class PyBool(val value: Boolean?) : PyASTPrimitive() {
    override fun generatePyString(): String {
        if (value == null) {
            return "None"
        }
        return value.toString().replaceFirstChar { it.uppercase() }
    }
}
