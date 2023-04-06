import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import tsl.common.model.*


fun test() {

    val module = SerializersModule {
        // TODO: using sealed classes could make this unnecessary
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
            subclass(FunctionUsesOnlyLocalVarsTest::class)

            subclass(ProgramExecutionTest::class)
            subclass(ProgramContainsTryExceptTest::class)
            subclass(ProgramCallsPrintTest::class)
            subclass(ProgramContainsLoopTest::class)
            subclass(ProgramImportsModuleTest::class)
            subclass(ProgramContainsKeywordTest::class)
            subclass(ProgramCallsFunctionTest::class)
            subclass(ProgramDefinesFunctionTest::class)

            defaultDeserializer { DefaultTest.serializer() }
        }
    }


//    val c = YamlConfiguration(polymorphismStyle = PolymorphismStyle.Property)
//    val yaml = Yaml {
//        this.serializersModule = module
//
//    }

    val json = Json {
        serializersModule = module
        prettyPrint = true
        encodeDefaults = true

    }

    val tree = TSL(
        validateFiles = true, requiredFiles = emptyList(), tslVersion = "1.0", tests = listOf(
            FunctionContainsLoopTest("f", ContainsCheck(mustContain = true, cannotContain = false))
        )
    )


    val s = json.encodeToString(TSL.serializer(), tree)

//    val s = yaml.encodeToString(TSL.serializer(), tree)

    debug { s }
}

