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
 *
 * ### It also parses the output, which it did not use to
 *
 * "Compiled OK" used to mean `compileTSL` returned without throwing. It does not follow that what
 * came back is Python: the emitter passes several kinds of value through untouched, so a spec
 * containing a malformed literal produces a perfectly good `String` that raises `SyntaxError` the
 * first time a student submits. The migration this tool signed off contains such a spec, and the
 * tool reported it as one of the 721.
 *
 * So each generated script is now handed to CPython's own parser. Needs `python3` on PATH; without
 * it the pass is skipped, loudly, because a run that only checks half of what it claims should say
 * so rather than print the same reassuring number.
 */
fun main(args: Array<String>) {
    val root = File(
        args.firstOrNull() ?: error("usage: -PspecTree=<dir of <exercise id>/tsl.json>")
    )
    require(root.isDirectory) { "not a directory: ${root.absolutePath}" }

    /**
     * Optional `-PspecDump=<dir>`: write every generated script out, one file per exercise.
     *
     * For the question this tool cannot otherwise answer — *what changed?* Run it before and after
     * an emitter change and diff the two directories, and the blast radius of "how many live
     * exercises does this alter" becomes a number rather than an argument. That is the same reason
     * `doc/core/api-shapes.json` is a file in git: the diff is the review artefact.
     */
    val dumpDir = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }

    val dirs = root.listFiles { f: File -> f.isDirectory }
        ?.sortedBy { it.name.toIntOrNull() ?: 0 }
        ?: error("cannot list ${root.absolutePath}")

    var compiled = 0
    val failures = mutableListOf<Pair<String, String>>()
    val unparseable = mutableListOf<Pair<String, String>>()
    val canParse = pythonAvailable()

    dirs.forEach { dir ->
        val spec = File(dir, "tsl.json")
        if (!spec.isFile) {
            failures += dir.name to "no tsl.json"
            return@forEach
        }
        try {
            val result = compileTSL(spec.readText(Charsets.UTF_8), "1.0.0", "tiivad", TSLSpecFormat.JSON)
            compiled++

            dumpDir?.let { d ->
                result.generatedScripts.forEachIndexed { i, script ->
                    File(d, if (i == 0) "${dir.name}.py" else "${dir.name}.$i.py").writeText(script)
                }
            }

            if (canParse) {
                result.generatedScripts.forEach { script ->
                    pythonSyntaxError(script)?.let { unparseable += dir.name to it }
                }
            }
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

    if (canParse) {
        println("not Python  : ${unparseable.size}")
        unparseable.forEach { (id, why) -> println("  $id  $why") }
    } else {
        println("not Python  : SKIPPED — no python3 on PATH, so the generated scripts were not parsed")
    }

    // Non-zero, so this can gate a migration in a script rather than relying on someone reading it.
    // An unparseable script counts: it is a spec that compiles and then fails on the first
    // submission, which is a worse outcome than one that fails here.
    val total = failures.size + unparseable.size
    if (total > 0) throw RuntimeException(
        "$total spec(s) failed: ${failures.size} would not compile, ${unparseable.size} compiled to non-Python"
    )
}

private fun pythonAvailable(): Boolean = runCatching {
    val p = ProcessBuilder("python3", "-c", "import ast").redirectErrorStream(true).start()
    p.waitFor() == 0
}.getOrDefault(false)

/**
 * `null` if [script] parses as Python, otherwise the first line of the error.
 *
 * CPython's own parser, because nothing else is an authority on what Python accepts and a
 * hand-rolled check would be a second, worse implementation of the thing being verified.
 */
private fun pythonSyntaxError(script: String): String? {
    val process = ProcessBuilder(
        "python3", "-c",
        "import ast, sys\n" +
                "try:\n" +
                "    ast.parse(sys.stdin.read())\n" +
                "except SyntaxError as e:\n" +
                "    sys.stdout.write('line %s: %s' % (e.lineno, e.msg))\n" +
                "    sys.exit(1)\n"
    ).redirectErrorStream(false).start()

    process.outputStream.use { it.write(script.toByteArray()) }
    val out = process.inputStream.bufferedReader().readText().trim()
    process.errorStream.bufferedReader().readText()
    return if (process.waitFor() == 0) null else out.ifBlank { "SyntaxError" }
}
