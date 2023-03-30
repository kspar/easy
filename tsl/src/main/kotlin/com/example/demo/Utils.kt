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
    val considerElementsOrder: Boolean? = false,
    val beforeMessage: String,
    val passedMessage: String,
    val failedMessage: String
)

@Serializable
data class GenericCheckLong(
    val checkType: CheckTypeLong,
    val nothingElse: Boolean? = null,
    val expectedValue: List<String>, // The field to be checked.
    val beforeMessage: String,
    val passedMessage: String,
    val failedMessage: String
)

@Serializable
data class OutputFileCheck(
    val fileName: String,
    val checkType: CheckType,
    val nothingElse: Boolean? = null,
    val expectedValue: List<String>, // The field to be checked.
    val considerElementsOrder: Boolean? = false,
    val beforeMessage: String,
    val passedMessage: String,
    val failedMessage: String
)

@Serializable
class ExceptionCheck(
    val mustNotThrowException: Boolean,
    val beforeMessage: String,
    val passedMessage: String,
    val failedMessage: String
)

@Serializable
class ContainsCheck(
    val mustNotContain: Boolean,
    val beforeMessage: String,
    val passedMessage: String,
    val failedMessage: String
)

@Serializable
class RecursiveCheck(
    val mustNotBeRecursive: Boolean,
    val beforeMessage: String,
    val passedMessage: String,
    val failedMessage: String
)

@Serializable
class CallsCheck(
    val mustNotCall: Boolean,
    val beforeMessage: String,
    val passedMessage: String,
    val failedMessage: String
)