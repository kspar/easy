package com.example.demo

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.decodeFromString
import tsl.common.model.TSL
import tsl.common.model.TSLFormat
import java.io.File


enum class TSLSpecFormat { JSON, YAML }

private val yamlConfiguration = YamlConfiguration(polymorphismStyle = PolymorphismStyle.Property)
private val TSLYAMLFormat = Yaml(configuration = yamlConfiguration)

data class CompiledResult(
    val generatedScripts: List<String>,
    val tslCompilerVersion: String,
    val backendID: String,
    val backendVersion: String
)


/**
 * A JSON spec, decoded and re-encoded through the same parser [compileTSL] uses.
 *
 * The round trip is the point: kotlinx's parser tolerates wui-era artefacts — a raw newline inside
 * a string, a missing delimiter — that ECMA-404 parsers reject, so a spec can grade for years while
 * the React editor's `JSON.parse` can never open it (EZ-1813). The re-encoding is strict JSON, and
 * because the decode is the compiler's own, anything it would lose could never have compiled.
 */
fun normalizeTSLSpec(tslSpec: String): String =
    TSLFormat.encodeToString(TSL.serializer(), TSLFormat.decodeFromString<TSL>(tslSpec))

fun compileTSL(
    tslSpec: String,
    tslCompilerVersion: String,
    backendID: String,
    format: TSLSpecFormat
): CompiledResult {

    when (backendID) {
        "tiivad" -> {
            val parseTree = when (format) {
                TSLSpecFormat.JSON -> TSLFormat.decodeFromString<TSL>(tslSpec)
                TSLSpecFormat.YAML -> TSLYAMLFormat.decodeFromString<TSL>(tslSpec)
            }

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
    val fileName = "tsl/src/main/resources/test_tsl.YAML"
    //val fileName = "tsl/src/main/resources/test_tsl.json"
    println(fileName)

    val inputText = File(fileName).readText(Charsets.UTF_8)

    val compiledResult = compileTSL(inputText, "1.0.0", "tiivad", TSLSpecFormat.YAML)
    println(compiledResult.backendID)
    println(compiledResult.backendVersion)
    println(compiledResult.generatedScripts[0])
    println(compiledResult.tslCompilerVersion)

    val parseTree = TSLYAMLFormat.decodeFromString<TSL>(inputText)
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
