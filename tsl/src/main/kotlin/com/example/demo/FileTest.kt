package com.example.demo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("file_exists")
data class FileExists(
    val defaultValue: String? = null
) : Test()


@Serializable
@SerialName("file_not_empty")
data class FileNotEmpty(
    val defaultValue: String? = null
) : Test()

@Serializable
@SerialName("file_is_python")
data class FileIsPython(
    val defaultValue: String? = null
) : Test()

