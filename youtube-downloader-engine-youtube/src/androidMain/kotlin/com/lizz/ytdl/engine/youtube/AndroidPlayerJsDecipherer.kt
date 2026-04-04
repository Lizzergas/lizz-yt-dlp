package com.lizz.ytdl.engine.youtube

import java.net.URLDecoder
import java.net.URLEncoder

internal class AndroidPlayerJsDecipherer {
    fun resolveCipheredFormats(
        formats: List<NativeAudioFormat>,
        playerJs: String,
    ): List<NativeAudioFormat> {
        val solver = AndroidSignatureSolver.tryCreate(playerJs) ?: return formats
        return formats.map { format ->
            if (format.url != null || format.signatureCipher == null) return@map format
            resolveCipheredFormat(format, solver) ?: format
        }
    }

    private fun resolveCipheredFormat(
        format: NativeAudioFormat,
        solver: AndroidSignatureSolver,
    ): NativeAudioFormat? {
        val cipher = format.signatureCipher ?: return null
        val parts = parseQueryString(cipher)
        val baseUrl = parts["url"] ?: return null
        val encrypted = parts["s"] ?: return null
        val sp = parts["sp"] ?: "signature"
        val signature = solver.decipher(encrypted)
        return format.copy(url = appendQuery(baseUrl, sp, signature))
    }

    private fun parseQueryString(value: String): Map<String, String> {
        return value.split('&').mapNotNull { token ->
            val eq = token.indexOf('=')
            if (eq < 0) return@mapNotNull null
            val key = URLDecoder.decode(token.substring(0, eq), Charsets.UTF_8.name())
            val decodedValue = URLDecoder.decode(token.substring(eq + 1), Charsets.UTF_8.name())
            key to decodedValue
        }.toMap()
    }

    private fun appendQuery(url: String, key: String, value: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return "$url$separator$key=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
    }
}

internal class AndroidSignatureSolver private constructor(
    private val operations: List<AndroidSignatureOperation>,
) {
    fun decipher(input: String): String {
        var chars = input.toMutableList()
        operations.forEach { operation -> chars = operation.apply(chars) }
        return chars.joinToString("")
    }

    companion object {
        fun tryCreate(playerJs: String): AndroidSignatureSolver? {
            val functionBody = extractFunctionBody(playerJs) ?: return null
            val helperName = Regex("""([\$\w]+)\.([\$\w]+)\(\w,(\d+)\)""")
                .find(functionBody)
                ?.groupValues
                ?.getOrNull(1)
                ?: return null
            val helperBody = extractHelperObject(playerJs, helperName) ?: return null
            val methodOps = parseHelperMethods(helperBody)
            if (methodOps.isEmpty()) return null

            val operations = buildList {
                val regex = Regex("""$helperName\.([\$\w]+)\(\w(?:,(\d+))?\)""")
                regex.findAll(functionBody).forEach { match ->
                    val methodName = match.groupValues[1]
                    val arg = match.groupValues.getOrNull(2)?.toIntOrNull()
                    val op = methodOps[methodName] ?: return@forEach
                    add(if (op is AndroidSignatureOperation.WithArg) op.withArg(arg ?: 0) else op)
                }
            }
            return operations.takeIf { it.isNotEmpty() }?.let(::AndroidSignatureSolver)
        }

        private fun extractFunctionBody(playerJs: String): String? {
            val patterns = listOf(
                Regex("""([\$\w]+)=function\((\w)\)\{\2=\2\.split\(""\);([\s\S]{0,2000}?)return\s+\2\.join\(""\)\}"""),
                Regex("""function\s+([\$\w]+)\((\w)\)\{\2=\2\.split\(""\);([\s\S]{0,2000}?)return\s+\2\.join\(""\)\}"""),
            )
            return patterns.firstNotNullOfOrNull { regex -> regex.find(playerJs)?.groupValues?.getOrNull(3) }
        }

        private fun extractHelperObject(playerJs: String, helperName: String): String? {
            val regexes = listOf(
                Regex("""var\s+$helperName=\{([\s\S]{0,4000}?)\};"""),
                Regex("""$helperName=\{([\s\S]{0,4000}?)\};"""),
            )
            return regexes.firstNotNullOfOrNull { it.find(playerJs)?.groupValues?.getOrNull(1) }
        }

        private fun parseHelperMethods(helperBody: String): Map<String, AndroidSignatureOperation> {
            val regex = Regex("""([\$\w]+):function\((\w)(?:,(\w))?\)\{([^{}]{0,300})\}""")
            return buildMap {
                regex.findAll(helperBody).forEach { match ->
                    val name = match.groupValues[1]
                    val body = match.groupValues[4]
                    val op = when {
                        body.contains("reverse()") -> AndroidSignatureOperation.Reverse
                        body.contains("splice(0,") || body.contains("slice(") -> AndroidSignatureOperation.WithArg { arg -> AndroidSignatureOperation.Drop(arg) }
                        body.contains("[0]") && body.contains("%") -> AndroidSignatureOperation.WithArg { arg -> AndroidSignatureOperation.Swap(arg) }
                        else -> null
                    }
                    if (op != null) put(name, op)
                }
            }
        }
    }
}

internal sealed interface AndroidSignatureOperation {
    fun apply(input: MutableList<Char>): MutableList<Char>

    data object Reverse : AndroidSignatureOperation {
        override fun apply(input: MutableList<Char>): MutableList<Char> = input.asReversed().toMutableList()
    }

    data class Drop(val count: Int) : AndroidSignatureOperation {
        override fun apply(input: MutableList<Char>): MutableList<Char> = input.drop(count).toMutableList()
    }

    data class Swap(val index: Int) : AndroidSignatureOperation {
        override fun apply(input: MutableList<Char>): MutableList<Char> {
            if (input.isEmpty()) return input
            val copy = input.toMutableList()
            val target = index % copy.size
            val first = copy[0]
            copy[0] = copy[target]
            copy[target] = first
            return copy
        }
    }

    class WithArg(private val builder: (Int) -> AndroidSignatureOperation) : AndroidSignatureOperation {
        override fun apply(input: MutableList<Char>): MutableList<Char> = input
        fun withArg(arg: Int): AndroidSignatureOperation = builder(arg)
    }
}
