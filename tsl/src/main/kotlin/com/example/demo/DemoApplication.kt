package com.example.demo

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.*

import kotlinx.serialization.modules.*
import java.io.File

@Serializable
data class TSL(
    val language: String = "python3",
    val validateFiles: Boolean,
    // The first file in the requiredFiles list is always the file to be executed.
    // We can expect from UI that there is always at least one file in the list.
    // TODO: Execution-tüüpi testide puhul pole vaja faili nime, alati kasutatakse peafaili listist.
    // TODO: Static tüüpi testide puhul oleks tarvis vajadusel määrata faili nimi, vastasel juhul otsitakse üle kõigi.
    val requiredFiles: List<String>,
    val tslVersion: String,
    val tests: List<Test>
)

@Serializable
abstract class Check {
    val beforeMessage: String? = null
    val passedMessage: String? = null
    val failedMessage: String? = null
}

@Serializable
abstract class Test {
    abstract fun getDefaultName(): String

    val points: Double = 1.0
    val id: Long? = null
    val name: String? = null
    val inputs: String? = null // TODO: Kaspar, kas selle jätame?
    val passedNext: Long? = null
    val failedNext: Long? = null
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
) : Test() {
    override fun getDefaultName(): String {
        return "DefaultTest DefaultName"
    }
}


// Declare different types of Tests
val module = SerializersModule {
    polymorphic(Test::class) {
        subclass(FunctionExecutionTest::class)
        subclass(FunctionContainsLoopTest::class)
        subclass(FunctionContainsKeywordTest::class)
        subclass(FunctionContainsReturnTest::class)
        subclass(FunctionCallsFunctionTest::class)
        subclass(FunctionIsRecursiveTest::class)
        subclass(FunctionCallsPrintTest::class)
        subclass(FunctionDefinesFunctionTest::class)
        subclass(FunctionImportsModuleTest::class)
        subclass(FunctionContainsTryExceptTest::class)
        subclass(FunctionIsPureTest::class)

        subclass(ProgramExecutionTest::class)
        subclass(ProgramContainsTryExceptTest::class)
        subclass(ProgramCallsPrintTest::class)
        subclass(ProgramContainsLoopTest::class)
        subclass(ProgramImportsModuleTest::class)
        subclass(ProgramContainsKeywordTest::class)
        subclass(ProgramCallsFunctionTest::class)
        subclass(ProgramDefinesFunctionTest::class)

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
        "tiivad" -> {
            val parseTree = f.decodeFromString<TSL>(tslSpec)

            // val irTree = IRTree(parseTree) TODO: Uncomment me if we will use intermediate tree (so far, we don't need)
            val compiler = Compiler(parseTree)
            val assessmentCode = compiler.generateAssessmentCodes()

            return CompiledResult(
                generatedScripts = listOf(assessmentCode),
                tslCompilerVersion = tslCompilerVersion,
                backendID = backendID,
                backendVersion = "?.?.?"
            )
        }
        else -> {
            throw NotImplementedError()
        }
    }
}


fun main() {
    // TODO: Lisage siia faili nimi, mis asub juurkaustas:
    //var fileName = "tsl/src/main/kotlin/com/example/demo/test_tsl.YAML"
    val fileName = "tsl/src/main/kotlin/com/example/demo/test_tsl.YAML"
    println(fileName)

    val inputText = File(fileName).readText(Charsets.UTF_8)

    val compiledResult = compileTSL(inputText, "1.0.0", "tiivad")
    println(compiledResult.backendID)
    println(compiledResult.backendVersion)
    println(compiledResult.generatedScripts[0])
    println(compiledResult.tslCompilerVersion)

    val parseTree = f.decodeFromString<TSL>(inputText)
    println(parseTree)

    val compiler = Compiler(parseTree)
    compiler.validateParseTree()
    val assessmentCode = compiler.generateAssessmentCodes()
    println(assessmentCode)
}

//var irTree = IRTree(parseTree)

//println("Compiled code:")
//var compiler = Compiler(irTree)
//var assessmentCode = compiler.generateAssessmentCode()
//println(assessmentCode)
