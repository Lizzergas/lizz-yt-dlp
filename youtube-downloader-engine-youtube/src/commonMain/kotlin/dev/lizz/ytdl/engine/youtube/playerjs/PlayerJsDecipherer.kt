package dev.lizz.ytdl.engine.youtube

internal object PlayerJsDecipherer {
    fun resolveProtectedFormats(
        formats: List<NativeAudioFormat>,
        playerJs: String,
    ): List<NativeAudioFormat> {
        val signatureSolver = SignatureSolver.tryCreate(playerJs)
        val nSolver = NSolver.tryCreate(playerJs)

        return formats.map { format ->
            val resolvedCipher = if (format.url == null && format.signatureCipher != null && signatureSolver != null) {
                resolveCipheredFormat(format, signatureSolver)
            } else format

            val rewrittenUrl = resolvedCipher.url?.let { url ->
                if (nSolver != null) NParamRewriter.rewrite(url, nSolver) else url
            }

            resolvedCipher.copy(url = rewrittenUrl)
        }
    }

    private fun resolveCipheredFormat(
        format: NativeAudioFormat,
        solver: SignatureSolver,
    ): NativeAudioFormat {
        val cipher = format.signatureCipher ?: return format
        val parts = UrlQuery.parse(cipher)
        val baseUrl = parts["url"] ?: return format
        val encrypted = parts["s"] ?: return format
        val sp = parts["sp"] ?: "signature"
        val signature = solver.decipher(encrypted)
        return format.copy(url = UrlQuery.append(baseUrl, sp, signature))
    }
}

internal class SignatureSolver private constructor(
    private val operations: List<SignatureOperation>,
) {
    fun decipher(input: String): String {
        var chars = input.toMutableList()
        operations.forEach { operation -> chars = operation.apply(chars) }
        return chars.joinToString("")
    }

    companion object {
        fun tryCreate(playerJs: String): SignatureSolver? {
            val functionBody = extractFunctionBody(playerJs) ?: return null
            val helperName = Regex("""([\$\w]+)\.([\$\w]+)\(\w,?(\d+)?\)""")
                .find(functionBody)
                ?.groupValues
                ?.getOrNull(1)
            val operations = if (helperName != null) {
                val helperBody = extractHelperObject(playerJs, helperName) ?: return null
                val methodOps = parseHelperMethods(helperBody)
                if (methodOps.isEmpty()) return null

                buildList {
                    val regex = Regex("""$helperName\.([\$\w]+)\(\w(?:,(\d+))?\)""")
                    regex.findAll(functionBody).forEach { match ->
                        val methodName = match.groupValues[1]
                        val arg = match.groupValues.getOrNull(2)?.toIntOrNull()
                        val op = methodOps[methodName] ?: return@forEach
                        add(if (op is SignatureOperation.WithArg) op.withArg(arg ?: 0) else op)
                    }
                }
            } else {
                parseInlineOperations(functionBody)
            }
            return operations.takeIf { it.isNotEmpty() }?.let(::SignatureSolver)
        }

        private fun extractFunctionBody(playerJs: String): String? {
            val patterns = listOf(
                Regex("""([\$\w]+)=function\((\w)\)\{\2=\2\.split\(""\);([\s\S]{0,2400}?)return\s+\2\.join\(""\)\}"""),
                Regex("""function\s+([\$\w]+)\((\w)\)\{\2=\2\.split\(""\);([\s\S]{0,2400}?)return\s+\2\.join\(""\)\}"""),
            )
            return patterns.firstNotNullOfOrNull { regex -> regex.find(playerJs)?.groupValues?.getOrNull(3) }
        }

        private fun extractHelperObject(playerJs: String, helperName: String): String? {
            val regexes = listOf(
                Regex("""var\s+$helperName=\{([\s\S]{0,6000}?)\};"""),
                Regex("""let\s+$helperName=\{([\s\S]{0,6000}?)\};"""),
                Regex("""const\s+$helperName=\{([\s\S]{0,6000}?)\};"""),
                Regex("""$helperName=\{([\s\S]{0,6000}?)\};"""),
            )
            return regexes.firstNotNullOfOrNull { it.find(playerJs)?.groupValues?.getOrNull(1) }
        }

    }
}

internal sealed interface SignatureOperation {
    fun apply(input: MutableList<Char>): MutableList<Char>

    data object Reverse : SignatureOperation {
        override fun apply(input: MutableList<Char>): MutableList<Char> = input.asReversed().toMutableList()
    }

    data class Drop(val count: Int) : SignatureOperation {
        override fun apply(input: MutableList<Char>): MutableList<Char> = input.drop(count).toMutableList()
    }

    data class Swap(val index: Int) : SignatureOperation {
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

    class WithArg(private val builder: (Int) -> SignatureOperation) : SignatureOperation {
        override fun apply(input: MutableList<Char>): MutableList<Char> = input
        fun withArg(arg: Int): SignatureOperation = builder(arg)
    }
}

internal class NSolver private constructor(
    private val operations: List<SignatureOperation>,
) {
    fun decipher(input: String): String {
        var chars = input.toMutableList()
        operations.forEach { operation -> chars = operation.apply(chars) }
        return chars.joinToString("")
    }

    companion object {
        fun tryCreate(playerJs: String): NSolver? {
            val name = extractFunctionName(playerJs) ?: return null
            val body = extractFunctionBody(playerJs, name) ?: return null
            val helperName = Regex("""([\$\w]+)\.([\$\w]+)\(\w,?(\d+)?\)""")
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
            val operations = if (helperName != null) {
                val helperBody = extractHelperObject(playerJs, helperName) ?: return null
                val methodOps = parseHelperMethods(helperBody)
                if (methodOps.isEmpty()) return null
                buildList {
                    val regex = Regex("""$helperName\.([\$\w]+)\(\w(?:,(\d+))?\)""")
                    regex.findAll(body).forEach { match ->
                        val methodName = match.groupValues[1]
                        val arg = match.groupValues.getOrNull(2)?.toIntOrNull()
                        val op = methodOps[methodName] ?: return@forEach
                        add(if (op is SignatureOperation.WithArg) op.withArg(arg ?: 0) else op)
                    }
                }
            } else {
                parseInlineOperations(body)
            }
            return operations.takeIf { it.isNotEmpty() }?.let(::NSolver)
        }

        private fun extractFunctionName(playerJs: String): String? {
            val patterns = listOf(
                Regex("""[?&]n=([\w$]+)"""),
                Regex("""\.get\("n"\)\s*&&\s*\(\w+=([\$\w]+)\("""),
                Regex("""\.get\("n"\)\s*&&\s*\([\w$]+=([\$\w]+)\([\w$]+\)\)"""),
                Regex("""\b([\$\w]{2,})=function\(\w\)\{[^{}]{0,120}\w=\w\.split\(""\)"""),
            )
            return patterns.firstNotNullOfOrNull { it.find(playerJs)?.groupValues?.getOrNull(1) }
        }

        private fun extractFunctionBody(playerJs: String, functionName: String): String? {
            val patterns = listOf(
                Regex("""$functionName=function\((\w)\)\{([\s\S]{0,2400}?)\}"""),
                Regex("""function\s+$functionName\((\w)\)\{([\s\S]{0,2400}?)\}"""),
            )
            return patterns.firstNotNullOfOrNull { it.find(playerJs)?.groupValues?.getOrNull(2) }
        }

        private fun extractHelperObject(playerJs: String, helperName: String): String? {
            val regexes = listOf(
                Regex("""var\s+$helperName=\{([\s\S]{0,6000}?)\};"""),
                Regex("""$helperName=\{([\s\S]{0,6000}?)\};"""),
            )
            return regexes.firstNotNullOfOrNull { it.find(playerJs)?.groupValues?.getOrNull(1) }
        }
    }
}

private fun parseHelperMethods(helperBody: String): Map<String, SignatureOperation> {
    val regex = Regex("""(?:"([\$\w]+)"|'([\$\w]+)'|([\$\w]+)):(?:function\((\w)(?:,(\w))?\)|\((\w)(?:,(\w))?\)\s*=>|([\$\w]+)\((\w)(?:,(\w))?\))\{([^{}]{0,500})\}""")
    return buildMap {
        regex.findAll(helperBody).forEach { match ->
            val name = listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[8]).firstOrNull { it.isNotBlank() } ?: return@forEach
            val body = match.groupValues[11]
            val op = when {
                body.contains("reverse()") -> SignatureOperation.Reverse
                body.contains("splice(0,") || body.contains("slice(") -> SignatureOperation.WithArg { arg -> SignatureOperation.Drop(arg) }
                body.contains("[0]") && body.contains("%") -> SignatureOperation.WithArg { arg -> SignatureOperation.Swap(arg) }
                else -> null
            }
            if (op != null) put(name, op)
        }
    }
}

private fun parseInlineOperations(functionBody: String): List<SignatureOperation> {
    return buildList {
        Regex("""\.reverse\(\)""").findAll(functionBody).forEach { add(SignatureOperation.Reverse) }
        Regex("""(?:splice\(0,|slice\()([0-9]+)\)""").findAll(functionBody).forEach { match ->
            add(SignatureOperation.Drop(match.groupValues[1].toInt()))
        }
    }
}

internal object NParamRewriter {
    fun rewrite(url: String, solver: NSolver): String {
        val nValue = UrlQuery.get(url, "n") ?: return url
        val rewritten = solver.decipher(nValue)
        return UrlQuery.set(url, "n", rewritten)
    }
}

internal object UrlQuery {
    fun parse(value: String): Map<String, String> {
        return value.split('&').mapNotNull { token ->
            val eq = token.indexOf('=')
            if (eq < 0) return@mapNotNull null
            decode(token.substring(0, eq)) to decode(token.substring(eq + 1))
        }.toMap()
    }

    fun get(url: String, key: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null
        return parse(query)[key]
    }

    fun append(url: String, key: String, value: String): String {
        val separator = if (url.contains('?')) '&' else '?'
        return "$url$separator$key=${encode(value)}"
    }

    fun set(url: String, key: String, value: String): String {
        val base = url.substringBefore('?')
        val query = url.substringAfter('?', "")
        val pairs = if (query.isEmpty()) mutableMapOf<String, String>() else parse(query).toMutableMap()
        pairs[key] = value
        val queryString = pairs.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        return if (queryString.isEmpty()) base else "$base?$queryString"
    }

    private fun encode(value: String): String {
        return buildString {
            value.encodeToByteArray().forEach { byte ->
                val c = byte.toInt().toChar()
                if (c.isLetterOrDigit() || c in listOf('-', '_', '.', '~')) append(c)
                else append("%${byte.toUByte().toString(16).uppercase().padStart(2, '0')}")
            }
        }
    }

    private fun decode(value: String): String {
        val bytes = mutableListOf<Byte>()
        val chars = mutableListOf<Char>()
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '%' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3)
                bytes += hex.toInt(16).toByte()
                index += 3
                continue
            }
            if (bytes.isNotEmpty()) {
                chars += bytes.toByteArray().decodeToString().toList()
                bytes.clear()
            }
            chars += if (ch == '+') ' ' else ch
            index++
        }
        if (bytes.isNotEmpty()) chars += bytes.toByteArray().decodeToString().toList()
        return chars.joinToString("")
    }
}
