package rip.kspar.ezspa

external fun decodeURIComponent(encoded: String): String

external fun encodeURIComponent(plain: String): String

fun String.decodeURIComponent(): String = decodeURIComponent(this)

fun String.encodeURIComponent(): String = encodeURIComponent(this)
