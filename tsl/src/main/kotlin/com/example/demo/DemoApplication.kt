package com.example.demo

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.decodeFromString
import tsl.common.model.TSL
import java.io.File


val c = YamlConfiguration(polymorphismStyle = PolymorphismStyle.Property)
val f = Yaml(configuration = c)

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
    val fileName = "tsl/src/main/resources/test_tsl.YAML"
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
