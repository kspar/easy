package com.example.demo

sealed class IRTest(
    val id: Int? = null,
    val name: String? = null,
    val testNamePython: String? = null,
    val points: Double? = null,
    val inputs: String? = null,
    val passedNext: String? = null,
    val failedNext: String? = null,
    val beforeMessage: String? = null,
    val passedMessage: String? = null,
    val failedMessage: String? = null,
    val visibleToUser: Boolean? = null,
)

class IRFileExists(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "file_exists",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFileNotEmpty(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "file_not_empty",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFileIsPython(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "file_is_python",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

fun getIRFileCheckTests(tests: List<Test>): List<IRTest> {
    //val irTests = mutableListOf<IRTest>()
    val changedList = tests.map { test ->
        when (test) {
            is FileExists -> IRFileExists(
                id = test.id,
                name = test.name,
                //testNamePython=test.testNamePython,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )

            is FileNotEmpty -> IRFileNotEmpty(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is FileIsPython -> IRFileIsPython(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            else -> throw NotImplementedError("We should never get here, right?!1")  // TODO: FIXME: 20 Jan 2022
            // Sealed class doesn't require an else clause???
        }
    }
    return changedList
}

fun getIRFileCheck(fileCheck: FileCheck): IRFileCheck {
    return IRFileCheck(fileCheck.fileName, getIRFileCheckTests(fileCheck.tests))
}

class IRProgramImportsModule(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_imports_module",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null,
    val moduleName: String
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRProgramImportsModuleFromSet(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_imports_module_from_set",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null,
    val moduleNames: List<String>
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRProgramImportsAnyModule(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_imports_any_module",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRProgramDefinesFunction(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_defines_function",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null,
    val functionName: String
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRProgramDefinesAnyFunction(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_defines_any_function",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRProgramContainsLoop(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_contains_loop",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRProgramCallsFunction(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_calls_function",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null,
    val functionName: String
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRProgramCallsFunctionFromSet(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_calls_function_from_set",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null,
    val functionsList: List<String>
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRProgramCallsPrint(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_calls_print",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRProgramContainsTryExcept(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_contains_try_except",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRProgramInputCountCorrect(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_input_count_correct",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRProgramRaisedException(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_raised_exception",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRProgramOutputCorrect(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_output_correct",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)


class IRProgramOutputFileCorrect(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "program_output_file_correct",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null,
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)


fun getIRProgramExecutionCheckTests(tests: List<Test>): List<IRTest> {
    //val irTests = mutableListOf<IRTest>()
    val changedList = tests.map { test ->
        when (test) {
            is ProgramInputCountCorrect -> IRProgramInputCountCorrect(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is ProgramRaisedException -> IRProgramRaisedException(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is ProgramOutputCorrect -> IRProgramOutputCorrect(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is ProgramOutputFileCorrect -> IRProgramOutputFileCorrect(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            else -> throw NotImplementedError("We should never get here, right?!1")  // TODO: FIXME: 20 Jan 2022
            // Sealed class doesn't require an else clause???
        }
    }
    return changedList
}

fun getIRProgramStaticCheckTests(tests: List<Test>): List<IRTest> {
    //val irTests = mutableListOf<IRTest>()
    val changedList = tests.map { test ->
        when (test) {
            is ProgramImportsModule -> IRProgramImportsModule(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser,
                moduleName = test.moduleName
            )
            is ProgramImportsModuleFromSet -> IRProgramImportsModuleFromSet(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser,
                moduleNames = test.moduleNames
            )
            is ProgramImportsAnyModule -> IRProgramImportsAnyModule(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is ProgramDefinesFunction -> IRProgramDefinesFunction(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser,
                functionName = test.functionName
            )
            is ProgramDefinesAnyFunction -> IRProgramDefinesAnyFunction(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is ProgramContainsLoop -> IRProgramContainsLoop(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is ProgramCallsFunction -> IRProgramCallsFunction(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser,
                functionName = test.functionName
            )
            is ProgramCallsFunctionFromSet -> IRProgramCallsFunctionFromSet(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser,
                functionsList = test.functionsList
            )
            is ProgramCallsPrint -> IRProgramCallsPrint(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is ProgramContainsTryExcept -> IRProgramContainsTryExcept(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            else -> {
                println(test)
                throw NotImplementedError("We should never get here, right?!1")
            }  // TODO: FIXME: 20 Jan 2022
            // Sealed class doesn't require an else clause???
        }
    }
    return changedList
}

fun getIRProgramStaticCheck(programStaticCheck: ProgramStaticCheck): IRProgramStaticCheck {
    return IRProgramStaticCheck(programStaticCheck.fileName, getIRProgramStaticCheckTests(programStaticCheck.tests))
}

fun getIRProgramExecutionCheck(programExecutionChecks: List<ProgramExecutionCheck>?): List<IRProgramExecutionCheck>? {
    if (programExecutionChecks == null) {
        return null
    }

    val progExecutionChecks = programExecutionChecks.map { program ->
        IRProgramExecutionCheck(
            program.fileName,
            program.userInputs,
            program.expectedOutput,
            getIRProgramExecutionCheckTests(program.tests),
            program.inputFiles,
            program.outputFiles
        )
    }
    return progExecutionChecks

}

class IRFunctionParamsCountCorrect(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_params_count_correct",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null,
    val numberOfParams: Int
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionImportsModule(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_imports_module",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null,
    val moduleName: String
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionImportsAnyModule(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_imports_any_module",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionDefinesFunction(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_defines_function",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null,
    val functionName: String
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionDefinesAnyFunction(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_defines_any_function",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionContainsLoop(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_contains_loop",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionCallsFunction(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_calls_function",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null,
    val functionName: String
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionCallsFunctionFromSet(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_calls_function_from_set",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null,
    val functionsList: List<String>
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionCallsPrint(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_calls_print",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionIsRecursive(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_is_recursive",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionContainsReturn(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_contains_return",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionInputCountCorrect(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_input_count_correct",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionReturnTypeCorrect(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_return_type_correct",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null,
    val expectedReturnType: String
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionReturned(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_returned",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)


class IRFunctionReturnValueCorrect(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_return_value_correct",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)

class IRFunctionRaisedException(
    id: Int? = null,
    name: String? = null,
    testNamePython: String = "function_raised_exception",
    points: Double? = null,
    inputs: String? = null,
    passedNext: String? = null,
    failedNext: String? = null,
    beforeMessage: String? = null,
    passedMessage: String? = null,
    failedMessage: String? = null,
    visibleToUser: Boolean? = null
) : IRTest(
    id,
    name,
    testNamePython,
    points,
    inputs,
    passedNext,
    failedNext,
    beforeMessage,
    passedMessage,
    failedMessage,
    visibleToUser
)


fun getIRFunctionStaticCheckTests(tests: List<Test>): List<IRTest> {
    val changedList = tests.map { test ->
        when (test) {
            is FunctionParamsCountCorrect -> IRFunctionParamsCountCorrect(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser,
                numberOfParams = test.numberOfParams
            )
            is FunctionImportsModule -> IRFunctionImportsModule(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser,
                moduleName = test.moduleName
            )
            is FunctionImportsAnyModule -> IRFunctionImportsAnyModule(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is FunctionDefinesFunction -> IRFunctionDefinesFunction(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser,
                functionName = test.functionName
            )
            is FunctionDefinesAnyFunction -> IRFunctionDefinesAnyFunction(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is FunctionContainsLoop -> IRFunctionContainsLoop(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is FunctionCallsFunction -> IRFunctionCallsFunction(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser,
                functionName = test.functionName
            )
            is FunctionCallsFunctionFromSet -> IRFunctionCallsFunctionFromSet(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser,
                functionsList = test.functionsList
            )
            is FunctionCallsPrint -> IRFunctionCallsPrint(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is FunctionIsRecursive -> IRFunctionIsRecursive(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is FunctionContainsReturn -> IRFunctionContainsReturn(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            else -> throw NotImplementedError("We should never get here, right?!1")  // TODO: FIXME: 20 Jan 2022
            // Sealed class doesn't require an else clause???
        }
    }
    return changedList
}

fun getIRFunctionExecutionCheckTests(tests: List<Test>): List<IRTest> {
    val changedList = tests.map { test ->
        when (test) {
            is FunctionInputCountCorrect -> IRFunctionInputCountCorrect(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is FunctionReturnTypeCorrect -> IRFunctionReturnTypeCorrect(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser,
                expectedReturnType = test.expectedReturnType
            )
            is FunctionReturnValueCorrect -> IRFunctionReturnValueCorrect(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is FunctionRaisedException -> IRFunctionRaisedException(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage,
                visibleToUser = test.visibleToUser
            )
            is FunctionReturned -> IRFunctionReturned(
                id = test.id,
                name = test.name,
                points = test.points,
                inputs = test.inputs,
                passedNext = test.passedNext,
                failedNext = test.failedNext,
                beforeMessage = test.beforeMessage,
                passedMessage = test.passedMessage,
                failedMessage = test.failedMessage
            )
            else -> {
                println(test)
                throw NotImplementedError("We should never get here, right?!1")
            }  // TODO: FIXME: 20 Jan 2022}
            // Sealed class doesn't require an else clause???
        }
    }
    return changedList
}

fun getIRFunctionExecutionCheck(functionExecutionCheck: List<FunctionExecutionCheck>?): List<IRFunctionExecutionCheck>? {
    if (functionExecutionCheck == null) {
        return null
    }

    val functionChecks = functionExecutionCheck.map { function ->
        IRFunctionExecutionCheck(
            function.fileName,
            function.functionName,
            getIRFunctionExecutionCheckTests(function.tests),
            function.expectedOutput,
            function.arguments,
            function.userInputsProgram,
            function.userInputsFunction
        )
    }

    return functionChecks
}

fun getIRFunctionStaticCheck(functionStaticCheck: List<FunctionStaticCheck>?): List<IRFunctionStaticCheck>? {
    if (functionStaticCheck == null) {
        return null
    }

    val functionChecks = functionStaticCheck.map { function ->
        IRFunctionStaticCheck(function.fileName, function.functionName, getIRFunctionStaticCheckTests(function.tests))
    }
    return functionChecks
}

class IRTree(parseTree: Check) {
    val language: String = parseTree.language
    val tslVersion: String = parseTree.tslVersion
    val fileCheck: IRFileCheck = getIRFileCheck(parseTree.fileCheck)
    val programStaticCheck: IRProgramStaticCheck = getIRProgramStaticCheck(parseTree.programStaticCheck)
    val programExecutionCheck: List<IRProgramExecutionCheck>? =
        getIRProgramExecutionCheck(parseTree.programExecutionCheck)
    val functionStaticCheck: List<IRFunctionStaticCheck>? = getIRFunctionStaticCheck(parseTree.functionStaticCheck)
    val functionExecutionCheck: List<IRFunctionExecutionCheck>? =
        getIRFunctionExecutionCheck(parseTree.functionExecutionCheck)
}

class IRFileCheck(
    val fileName: String,
    val tests: List<IRTest>
)

class IRProgramStaticCheck(
    val fileName: String,
    val tests: List<IRTest>
)

class IRProgramExecutionCheck(
    val fileName: String,
    val userInputs: List<String>?,
    val expectedOutput: String?,
    val tests: List<IRTest>,
    val inputFiles: List<TestFile>? = null,
    val outputFiles: List<TestFile>? = null
)

class IRFunctionStaticCheck(
    val fileName: String,
    val functionName: String,
    val tests: List<IRTest>
)

class IRFunctionExecutionCheck(
    val fileName: String,
    val functionName: String,
    val tests: List<IRTest>,
    val expectedOutput: String? = null,
    val arguments: String? = null,
    val userInputsProgram: List<String>? = null,
    val userInputsFunction: List<String>? = null
)