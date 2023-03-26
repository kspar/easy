package com.example.demo

import kotlinx.serialization.Serializable

enum class CheckType {
    ALL_OF_THESE, ANY_OF_THESE, MISSING_AT_LEAST_ONE_OF_THESE, NONE_OF_THESE
}

enum class CheckTypeLong {
    ALL_OF_THESE, ANY_OF_THESE, ANY, NONE_OF_THESE, MISSING_AT_LEAST_ONE_OF_THESE, NONE
}

@Serializable
class FileData(
    val fileName: String,
    val fileContent: String
)

// TODO: Kui on nt vaja kontrollida nimede olemasolu, siis kuidas me kontrollime,
//  et näiteks 3 nime eksisteerib väljundis, aga 2 nime mitte?

@Serializable
data class GenericCheck(
    val checkType: CheckType,
    val nothingElse: Boolean? = null,
    val expectedValue: List<String>, // The field to be checked.
    val considerElementsOrder: Boolean? = false
) : Check()

@Serializable
data class GenericCheckLong(
    val checkType: CheckTypeLong,
    val nothingElse: Boolean? = null,
    val expectedValue: List<String> // The field to be checked.
) : Check()

@Serializable
data class OutputFileCheck(
    val fileName: String,
    val checkType: CheckType,
    val nothingElse: Boolean? = null,
    val expectedValue: List<String>, // The field to be checked.
    val considerElementsOrder: Boolean? = false
) : Check()

@Serializable
class ExceptionCheck(
    val mustNotThrowException: Boolean
) : Check()

@Serializable
class ContainsCheck(
    val mustNotContain: Boolean
) : Check()

@Serializable
class RecursiveCheck(
    val mustNotBeRecursive: Boolean
) : Check()

@Serializable
class CallsCheck(
    val mustNotCall: Boolean
) : Check()