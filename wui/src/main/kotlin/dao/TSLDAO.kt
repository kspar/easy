package dao

import EzDate
import EzDateSerializer
import debug
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import queries.ReqMethod
import queries.fetchEms
import queries.http200
import queries.parseTo
import rip.kspar.ezspa.doInPromise
import kotlin.js.Promise

object TSLDAO {

    enum class FORMAT { JSON, YAML }

    @Serializable
    data class CompileResult(
        val scripts: List<CompiledScript>?,
        val feedback: String?,
        val meta: Meta?,
    )

    @Serializable
    data class CompiledScript(
        val name: String,
        val value: String,
    )

    @Serializable
    data class Meta(
        @Serializable(with = EzDateSerializer::class)
        val timestamp: EzDate,
        val compiler_version: String,
        val backend_id: String,
        val backend_version: String,
    )

    fun compile(tslSpec: String, format: FORMAT): Promise<CompileResult> = doInPromise {
        debug { "Compiling TSL spec" }

        val body = mapOf(
            "tsl_spec" to tslSpec,
            "format" to format
        )

        fetchEms("/tsl/compile", ReqMethod.POST, body,
            successChecker = { http200 }
        ).await().parseTo(CompileResult.serializer()).await()
    }
}