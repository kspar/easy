package com.example.demo

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.*

import kotlinx.serialization.modules.*
import java.io.File
import java.nio.file.Paths

@Serializable
data class TSL(
    val language: String = "python3",
    val validateFiles: Boolean, // TODO - Need oleks siis fikseeritud eelkontrollid faili(de?)le. Kompilaator peab
    // TODO genereerima mingi funktsiooni väljakutse, mis valideerib failid (exists, not empty ja is_python).
    // Root TSL elemendis võiks olla failide list, mille kohta lepime kokku, et esimene fail on alati käivitatav peafail.
    // Võib ka praegu TSL tasemel eeldada, et listis on alati vähemalt üks element - teeme nii,
    // et UI täidab selle eelduse (kui on tühi, siis kompilaatori error).
    // Execution-tüüpi testide puhul pole vaja faili nime, alati kasutatakse peafaili listist.
    // Static tüüpi testide puhul oleks tarvis vajadusel määrata faili nimi, vastasel juhul otsitakse üle kõigi.
    val requiredFiles: List<String>, // TODO: Esimene fail oleks põhifail (mida käivitatakse)
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
    val points: Double = 1.0
    val id: Int? = null
    val name: String? = null
    val inputs: String? = null // TODO: Kaspar, kas selle jätame?
    val passedNext: String? = null
    val failedNext: String? = null
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
        subclass(FunctionExecutionTest::class)
        subclass(FunctionContainsLoopTest::class)
        subclass(FunctionContainsKeywordTest::class)
        subclass(FunctionContainsReturnTest::class)
        subclass(FunctionCallsFunctionTest::class)
        subclass(FunctionIsRecursiveTest::class)
        subclass(FunctionDefinesFunctionTest::class)
        subclass(FunctionImportsModuleTest::class)
        subclass(FunctionContainsTryExceptTest::class)
        subclass(FunctionUsesOnlyLocalVarsTest::class)

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
        "Tiivad" -> {
            val parseTree = f.decodeFromString<Check>(tslSpec)

            // val irTree = IRTree(parseTree) TODO: Uncomment me
            val irTree = TSL("python3", false, listOf("file.py"), "1.0", listOf()) // TODO: RemoveMe
            val compiler = Compiler(irTree)
            val assessmentCode = compiler.generateAssessmentCodes()

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
    var fileName = "tsl/src/main/kotlin/com/example/demo/test_tsl.YAML"
    println(fileName)

    var inputText = File(fileName).readText(Charsets.UTF_8)
    var parseTree = f.decodeFromString<TSL>(inputText)
    println(parseTree)

    var compiler = Compiler(parseTree)
    var assessmentCode = compiler.generateAssessmentCodes()
    println(assessmentCode)
}

//var irTree = IRTree(parseTree)

//println("Compiled code:")
//var compiler = Compiler(irTree)
//var assessmentCode = compiler.generateAssessmentCode()
//println(assessmentCode)
