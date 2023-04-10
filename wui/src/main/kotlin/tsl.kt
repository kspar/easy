import kotlinx.serialization.json.Json
import tsl.common.model.ContainsCheck
import tsl.common.model.FunctionContainsLoopTest
import tsl.common.model.TSL


fun test() {

    val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    val tree = TSL(
        validateFiles = true, requiredFiles = emptyList(), tslVersion = "1.0", tests = listOf(
            FunctionContainsLoopTest("f", ContainsCheck(false, "test1", "ok", "not ok"))
        )
    )

    val s = json.encodeToString(TSL.serializer(), tree)

    debug { s }
}

