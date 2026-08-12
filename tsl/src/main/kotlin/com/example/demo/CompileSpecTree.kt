package com.example.demo

import java.io.File

/**
 * Compiles every TSL spec in a directory tree and reports what failed.
 *
 *     ./gradlew -q :tsl:compileSpecTree -PspecTree=doc/core/tsl-migration/migrated/exercises
 *
 * The tree is one directory per exercise, named with its id, each holding a `tsl.json` — the layout
 * `explode.py` and `migrate.py` write (see `doc/core/tsl-migration/`).
 *
 * This exists for spec migrations, and it is the step in one that actually proves something. A
 * migration script can be reasoned about, reviewed, and still be wrong about what the compiler
 * accepts; the only authority on that is the compiler. So the check is to run the real
 * [compileTSL] over the whole corpus and count, rather than to trust a rewrite that looks right.
 *
 * Its value is in being run *twice* — against the tree before migrating and the tree after. The
 * before number is what makes the after number mean anything: a harness that reports zero failures
 * because it silently compiled nothing looks exactly like a successful migration. During EZ-1607
 * the un-migrated corpus gave 532 compiled and 189 failed, and the migrated one 721 and 0.
 */
fun main(args: Array<String>) {
    val root = File(
        args.firstOrNull() ?: error("usage: -PspecTree=<dir of <exercise id>/tsl.json>")
    )
    require(root.isDirectory) { "not a directory: ${root.absolutePath}" }

    val dirs = root.listFiles { f: File -> f.isDirectory }
        ?.sortedBy { it.name.toIntOrNull() ?: 0 }
        ?: error("cannot list ${root.absolutePath}")

    var compiled = 0
    val failures = mutableListOf<Pair<String, String>>()

    dirs.forEach { dir ->
        val spec = File(dir, "tsl.json")
        if (!spec.isFile) {
            failures += dir.name to "no tsl.json"
            return@forEach
        }
        try {
            compileTSL(spec.readText(Charsets.UTF_8), "1.0.0", "tiivad", TSLSpecFormat.JSON)
            compiled++
        } catch (e: Throwable) {
            // Throwable, not Exception: a spec deep enough to blow the stack in the compiler is a
            // failure to report, not a reason to abandon the other seven hundred.
            val why = (e.message ?: e::class.simpleName ?: "unknown").lines().first().take(300)
            failures += dir.name to why
        }
    }

    println("compiled OK : $compiled")
    println("failed      : ${failures.size}")
    failures.forEach { (id, why) -> println("  $id  $why") }

    // Non-zero, so this can gate a migration in a script rather than relying on someone reading it.
    if (failures.isNotEmpty()) throw RuntimeException("${failures.size} spec(s) failed to compile")
}
