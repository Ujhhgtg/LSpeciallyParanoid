package dev.ujhhgtg.lsparanoid.processor

/** Receives each emitted literal occurrence independently of its storage backend. */
fun interface StringRegistrar {
    fun registerString(string: String): Long
}
