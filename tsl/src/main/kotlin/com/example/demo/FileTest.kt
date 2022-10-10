package com.example.demo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// TODO: Viia üle Tiibade sisse.
// TODO: Eelkontroll, mis kujul see läheb Tiibadele ja mis kujul vastus tuleb?
// TODO: 2. Tagasiside JSON schemasse võiks lisada ühe top-level välja preEvaluateError: String? - kui on täidetud, siis järelikult ühtegi testi ei käivitatud ja kasutajale tuleb anda tagasisideks ainult selles väljas olev sõnum (ja punktid: 0). Kui on null, siis on tavaline olukord.

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
