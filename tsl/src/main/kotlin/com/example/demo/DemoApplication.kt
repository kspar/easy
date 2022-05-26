package com.example.demo

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.*

import kotlinx.serialization.modules.*
import java.io.File

@Serializable
data class Check(
    val language: String = "python3",
    val tslVersion: String,
    val fileCheck: FileCheck,
    val programStaticCheck: ProgramStaticCheck,
    val programExecutionCheck: List<ProgramExecutionCheck>? = null,
    val functionStaticCheck: List<FunctionStaticCheck>? = null,
    val functionExecutionCheck: List<FunctionExecutionCheck>? = null
)

@Serializable
data class FileCheck(
    val fileName: String,
    val tests: List<Test>
)

@Serializable
data class ProgramStaticCheck(
    val fileName: String,
    val tests: List<Test>
)

@Serializable
data class TestFile(
    val fileName: String,
    val fileContent: String
)

@Serializable
data class ProgramExecutionCheck(
    val fileName: String,
    val userInputs: List<String>? = null,
    val expectedOutput: String? = null,
    val tests: List<Test>,
    val inputFiles: List<TestFile>? = null,
    val outputFiles: List<TestFile>? = null
)

@Serializable
data class FunctionStaticCheck(
    val fileName: String,
    val functionName: String,
    val tests: List<Test>
)

@Serializable
data class FunctionExecutionCheck(
    val fileName: String,
    val functionName: String,
    val tests: List<Test>,
    val expectedOutput: String? = null,
    val arguments: String? = null,
    val userInputsProgram: List<String>? = null,
    val userInputsFunction: List<String>? = null
)

@Serializable
abstract class Test {
    val id: Int? = null
    val name: String? = null
    val points: Double = 1.0
    val inputs: String? = null
    val passedNext: String? = null
    val failedNext: String? = null
    val beforeMessage: String? = null
    val passedMessage: String? = null
    val failedMessage: String? = null
    val visibleToUser: Boolean? = null
    // val programOutput: String? = null
    //output_files
    //prog_inputs
    //return_type
    //return_value
    //fun_arguments
    //fun_param_count
}

@Serializable
data class DefaultTest(
    val defaultValue: Int? = null
) : Test()


// Declare different types of Tests
val module = SerializersModule {
    polymorphic(Test::class) {
        subclass(FileExists::class)
        subclass(FileNotEmpty::class)
        subclass(FileIsPython::class)
        subclass(ProgramImportsModule::class)
        subclass(ProgramImportsModuleFromSet::class)
        subclass(ProgramImportsAnyModule::class)
        subclass(ProgramDefinesFunction::class)
        subclass(ProgramDefinesAnyFunction::class)
        subclass(ProgramContainsLoop::class)
        subclass(ProgramCallsFunction::class)
        subclass(ProgramCallsFunctionFromSet::class)
        subclass(ProgramCallsPrint::class)
        subclass(ProgramInputCountCorrect::class)
        subclass(ProgramRaisedException::class)
        subclass(ProgramContainsTryExcept::class)
        subclass(ProgramOutputCorrect::class)
        subclass(ProgramOutputFileCorrect::class)
        subclass(FunctionParamsCountCorrect::class)
        subclass(FunctionImportsModule::class)
        subclass(FunctionImportsAnyModule::class)
        subclass(FunctionDefinesFunction::class)
        subclass(FunctionDefinesAnyFunction::class)
        subclass(FunctionContainsLoop::class)
        subclass(FunctionCallsFunction::class)
        subclass(FunctionCallsFunctionFromSet::class)
        subclass(FunctionCallsPrint::class)
        subclass(FunctionIsRecursive::class)
        subclass(FunctionContainsReturn::class)
        subclass(FunctionInputCountCorrect::class)
        subclass(FunctionReturned::class)
        subclass(FunctionReturnTypeCorrect::class)
        subclass(FunctionReturnValueCorrect::class)
        subclass(FunctionRaisedException::class)
        default { DefaultTest.serializer() }
    }
}


val c = YamlConfiguration(polymorphismStyle = PolymorphismStyle.Property)
val f = Yaml(serializersModule = module, configuration = c)

data class CompiledResult(
    val generatedScripts: List<String>,
    val tslCompilerVersion: String,
    val backendID: String,
    val backendVersion: String
)

fun compileTSL(tslSpec: String, tslCompilerVersion: String, backendID: String): CompiledResult {
    when (backendID) {
        "Tiivad" -> {
            val parseTree = f.decodeFromString<Check>(tslSpec)
            val irTree = IRTree(parseTree)
            val compiler = Compiler(irTree)
            val assessmentCode = compiler.generateAssessmentCode()

            return CompiledResult(
                generatedScripts = listOf(assessmentCode),
                tslCompilerVersion = tslCompilerVersion,
                backendID = backendID,
                backendVersion = "?.?.?"
            )
        }
        else -> {
            throw NotImplementedError();
        }
    }
}

fun main() {
    // TODO: Lisage siia faili nimi, mis asub juurkaustas:
    var fileName = "tsl/src/test/resources/ylesanne17.YAML"











    println(fileName)
    var inputText = File(fileName).readText(Charsets.UTF_8)
    var parseTree = f.decodeFromString<Check>(inputText)

    var irTree = IRTree(parseTree)

    println("Compiled code:")
    var compiler = Compiler(irTree)
    var assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)
/*    var fileName = "ylesanne1.YAML"
    println(fileName)
    var inputText = File(fileName).readText(Charsets.UTF_8)
    var parseTree = f.decodeFromString<Check>(inputText)

    var irTree = IRTree(parseTree)

    println("Compiled code:")
    var compiler = Compiler(irTree)
    var assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne2.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne3.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne4.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne5.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne6.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne7.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne8.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne9.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne10.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne11.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne12.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne13.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne14.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne15.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne17.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)

    fileName = "ylesanne1_demo.YAML"
    println(fileName)
    inputText = File(fileName).readText(Charsets.UTF_8)
    parseTree = f.decodeFromString<Check>(inputText)

    irTree = IRTree(parseTree)

    println("Compiled code:")
    compiler = Compiler(irTree)
    assessmentCode = compiler.generateAssessmentCode()
    println(assessmentCode)*/
}
