package dj.sentia.core

/**
 * Petit lecteur / écrivain JSON sans dépendance (testable hors Android). Valeurs : Map, List, String, Double, Boolean, null.
 * Sert au protocole de la conversation en direct, pour tester le décodage des messages du serveur sans émulateur.
 */
object MiniJson {
    class ParseError(message: String) : Exception(message)

    fun quote(s: String): String {
        val b = StringBuilder(s.length + 2).append('"')
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' -> b.append(String.format("\\u%04x", c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw ParseError("texte en trop à ${p.i}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?>? = try { parse(text) as? Map<String, Any?> } catch (_: ParseError) { null }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek(): Char = if (i < s.length) s[i] else throw ParseError("fin inattendue")

        fun value(): Any? {
            ws()
            return when (peek()) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseError("valeur invalide à $i")
            i += word.length
            return v
        }

        private fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDoubleOrNull() ?: throw ParseError("nombre invalide à $st")
        }

        private fun str(): String {
            i++ // "
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseError("chaîne non terminée")
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (i >= s.length) throw ParseError("échappement incomplet")
                        when (val e = s[i++]) {
                            'n' -> b.append('\n'); 'r' -> b.append('\r'); 't' -> b.append('\t')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseError("\\u incomplet")
                                b.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw ParseError("\\u invalide"))
                                i += 4
                            }
                            else -> b.append(e) // " \ /
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (peek() == ']') { i++; return out }
            while (true) {
                out.add(value())
                ws()
                when (peek()) { ',' -> i++; ']' -> { i++; return out }; else -> throw ParseError("tableau invalide à $i") }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (peek() == '}') { i++; return out }
            while (true) {
                ws()
                if (peek() != '"') throw ParseError("clé attendue à $i")
                val k = str()
                ws()
                if (peek() != ':') throw ParseError(": attendu à $i")
                i++
                out[k] = value()
                ws()
                when (peek()) { ',' -> i++; '}' -> { i++; return out }; else -> throw ParseError("objet invalide à $i") }
            }
        }
    }
}
