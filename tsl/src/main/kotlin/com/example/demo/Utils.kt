package com.example.demo

import kotlinx.serialization.Serializable

enum class StringCheckType {
    ALL_OF_THESE, ANY_OF_THESE, MISSING_AT_LEAST_ONE_OF_THESE, NONE_OF_THESE
}

enum class StringCheckTypeLong {
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
data class StandardOutputCheck(
    val stringCheckType: StringCheckType,
    val nothingElse: Boolean? = null,
    val expectedOutput: List<String>, // The field to be checked.
    val considerElementsOrder: Boolean? = null
) : Check()

@Serializable
data class StandardOutputCheckLong(
    val stringCheckType: StringCheckTypeLong,
    val nothingElse: Boolean? = null,
    val expectedOutput: List<String> // The field to be checked.
) : Check()

@Serializable
data class OutputFileCheck(
    val fileName: String,
    val stringCheckType: StringCheckType,
    val nothingElse: Boolean? = null,
    val expectedOutput: List<String>, // The field to be checked.
    val considerElementsOrder: Boolean? = null
) : Check()

@Serializable
class ExceptionCheck(
    val mustThrowException: Boolean,
    val cannotThrowException: Boolean
) : Check()

@Serializable
class ContainsCheck(
    val mustContain: Boolean,
    val cannotContain: Boolean
) : Check()

@Serializable
class RecursiveCheck(
    val mustBeRecursive: Boolean,
    val cannotBeRecursive: Boolean
) : Check()

@Serializable
class CallsCheck(
    val mustCallPrint: Boolean,
    val cannotCallPrint: Boolean
) : Check()