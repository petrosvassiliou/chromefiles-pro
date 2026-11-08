package de.chromefiles.app.data.media

import java.util.Locale

/** Token-Klassen für die Syntaxhervorhebung im QuickView-Code-Viewer. */
enum class TokenType { KEYWORD, TYPE, STRING, NUMBER, COMMENT, ANNOTATION, PROPERTY, TAG, HEADING, EMPHASIS, CODE, LINK, VARIABLE }

data class HighlightSpan(val start: Int, val end: Int, val type: TokenType)

/**
 * Leichtgewichtiger, regex-basierter Syntax-Highlighter (ohne Abhängigkeiten, daher JVM-testbar).
 * Unterstützt Kotlin/Java, JavaScript/TypeScript, Python, Rust, Go, C/C++, Shell, SQL, JSON, YAML,
 * XML/HTML, CSS und Markdown. Mehrzeilige Kommentare und Strings werden korrekt erkannt,
 * weil immer der gesamte Text in einem Durchlauf gescannt wird.
 */
object SyntaxHighlighter {

    enum class Language { KOTLIN, JAVA, JAVASCRIPT, PYTHON, RUST, GO, C, SHELL, SQL, JSON, YAML, XML, CSS, MARKDOWN, PLAIN }

    fun languageFor(extension: String): Language = when (extension.lowercase(Locale.ROOT)) {
        "kt", "kts", "gradle" -> Language.KOTLIN
        "java" -> Language.JAVA
        "js", "mjs", "cjs", "ts", "tsx", "jsx" -> Language.JAVASCRIPT
        "py" -> Language.PYTHON
        "rs" -> Language.RUST
        "go" -> Language.GO
        "c", "h", "cpp", "hpp", "cc" -> Language.C
        "sh", "bash", "zsh", "env", "conf", "ini", "toml", "properties" -> Language.SHELL
        "sql" -> Language.SQL
        "json" -> Language.JSON
        "yml", "yaml" -> Language.YAML
        "xml", "html", "htm", "svg" -> Language.XML
        "css", "scss" -> Language.CSS
        "md", "markdown" -> Language.MARKDOWN
        else -> Language.PLAIN
    }

    private val KEYWORDS: Map<Language, Set<String>> = mapOf(
        Language.KOTLIN to setOf(
            "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when", "for", "while",
            "do", "return", "break", "continue", "try", "catch", "finally", "throw", "is", "in", "as", "null", "true", "false",
            "this", "super", "data", "sealed", "enum", "open", "abstract", "override", "private", "protected", "internal",
            "public", "companion", "suspend", "inline", "reified", "lateinit", "const", "typealias", "by", "init", "constructor"
        ),
        Language.JAVA to setOf(
            "package", "import", "class", "interface", "enum", "record", "extends", "implements", "public", "private", "protected",
            "static", "final", "abstract", "void", "if", "else", "switch", "case", "default", "for", "while", "do", "return",
            "break", "continue", "try", "catch", "finally", "throw", "throws", "new", "this", "super", "null", "true", "false",
            "int", "long", "double", "float", "boolean", "char", "byte", "short", "var", "synchronized", "instanceof"
        ),
        Language.JAVASCRIPT to setOf(
            "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break",
            "continue", "class", "extends", "new", "this", "super", "import", "export", "from", "async", "await", "try", "catch",
            "finally", "throw", "typeof", "instanceof", "null", "undefined", "true", "false", "interface", "type", "enum",
            "implements", "public", "private", "protected", "readonly", "of", "in", "yield", "static"
        ),
        Language.PYTHON to setOf(
            "def", "class", "return", "if", "elif", "else", "for", "while", "in", "not", "and", "or", "is", "None", "True",
            "False", "import", "from", "as", "with", "try", "except", "finally", "raise", "lambda", "yield", "pass", "break",
            "continue", "global", "nonlocal", "async", "await", "self"
        ),
        Language.RUST to setOf(
            "fn", "let", "mut", "pub", "struct", "enum", "impl", "trait", "use", "mod", "crate", "self", "Self", "super", "if",
            "else", "match", "for", "while", "loop", "return", "break", "continue", "as", "ref", "move", "async", "await",
            "where", "type", "const", "static", "unsafe", "true", "false", "dyn"
        ),
        Language.GO to setOf(
            "package", "import", "func", "var", "const", "type", "struct", "interface", "map", "chan", "if", "else", "for",
            "range", "switch", "case", "default", "return", "break", "continue", "go", "defer", "select", "nil", "true", "false"
        ),
        Language.C to setOf(
            "int", "long", "short", "char", "float", "double", "void", "unsigned", "signed", "const", "static", "struct", "union",
            "enum", "typedef", "if", "else", "for", "while", "do", "switch", "case", "default", "return", "break", "continue",
            "sizeof", "class", "public", "private", "protected", "namespace", "using", "template", "typename", "auto",
            "nullptr", "true", "false", "new", "delete", "include", "define", "ifdef", "ifndef", "endif"
        ),
        Language.SHELL to setOf(
            "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case", "esac", "function",
            "return", "export", "local", "readonly", "echo", "exit", "source", "true", "false"
        ),
        Language.SQL to setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table", "drop", "alter",
            "index", "join", "left", "right", "inner", "outer", "on", "and", "or", "not", "null", "is", "as", "group", "by",
            "order", "having", "limit", "offset", "distinct", "primary", "key", "foreign", "references", "default", "union"
        ),
        Language.JSON to setOf("true", "false", "null"),
        Language.YAML to setOf("true", "false", "null", "yes", "no", "on", "off"),
        Language.CSS to setOf("important", "inherit", "initial", "none", "auto")
    )

    private const val NUMBER = """(?<num>\b(?:0[xX][0-9a-fA-F_]+|\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?)[fFlLuU]?\b)"""
    private const val DQ = """"(?:\\.|[^"\\\n])*""""
    private const val SQ = """'(?:\\.|[^'\\\n])*'"""
    private const val IDENT = """(?<id>\b[A-Za-z_][A-Za-z0-9_]*\b)"""

    private val patterns: Map<Language, Regex> = buildMap {
        val cLike = { extraStrings: String ->
            Regex(
                """(?<cmt>//[^\n]*|/\*[\s\S]*?(?:\*/|$))|(?<str>$extraStrings$DQ|$SQ)|(?<ann>@[A-Za-z_][\w.]*)|$NUMBER|$IDENT"""
            )
        }
        put(Language.KOTLIN, cLike("\"\"\"[\\s\\S]*?(?:\"\"\"|$)|"))
        put(Language.JAVA, cLike("\"\"\"[\\s\\S]*?(?:\"\"\"|$)|"))
        put(Language.JAVASCRIPT, cLike("`(?:\\\\.|[^`\\\\])*`|"))
        put(Language.RUST, cLike(""))
        put(Language.GO, cLike("`[^`]*`|"))
        put(Language.C, Regex("""(?<cmt>//[^\n]*|/\*[\s\S]*?(?:\*/|$))|(?<ann>#\s*[a-z]+)|(?<str>$DQ|$SQ)|$NUMBER|$IDENT"""))
        put(
            Language.PYTHON,
            Regex("""(?<cmt>#[^\n]*)|(?<str>""${'"'}[\s\S]*?(?:""${'"'}|$)|'''[\s\S]*?(?:'''|$)|$DQ|$SQ)|(?<ann>@[A-Za-z_][\w.]*)|$NUMBER|$IDENT""")
        )
        put(Language.SHELL, Regex("""(?<cmt>(?:^|(?<=\s))#[^\n]*)|(?<str>$DQ|$SQ)|(?<var>\$\{?[A-Za-z_][A-Za-z0-9_]*\}?)|$NUMBER|$IDENT"""))
        put(Language.SQL, Regex("""(?<cmt>--[^\n]*|/\*[\s\S]*?(?:\*/|$))|(?<str>$SQ|$DQ)|$NUMBER|$IDENT"""))
        put(Language.JSON, Regex("""(?<prop>$DQ)(?=\s*:)|(?<str>$DQ)|$NUMBER|$IDENT"""))
        put(
            Language.YAML,
            Regex("""(?<cmt>(?:^|(?<=\s))#[^\n]*)|(?<prop>(?m:^[ \t]*-?[ \t]*[\w.\-"']+)(?=[ \t]*:(?:\s|$)))|(?<str>$DQ|$SQ)|$NUMBER|$IDENT""")
        )
        put(
            Language.XML,
            Regex("""(?<cmt><!--[\s\S]*?(?:-->|$))|(?<tag></?[A-Za-z][\w:.\-]*|/?>)|(?<prop>\b[\w:\-]+)(?==)|(?<str>$DQ|$SQ)""")
        )
        put(
            Language.CSS,
            Regex("""(?<cmt>/\*[\s\S]*?(?:\*/|$))|(?<str>$DQ|$SQ)|(?<prop>[\w\-]+)(?=\s*:[^{]*;)|(?<num>-?\d+(?:\.\d+)?(?:px|em|rem|%|vh|vw|s|ms|deg)?\b|#[0-9a-fA-F]{3,8}\b)|(?<tag>[.#]?[A-Za-z][\w\-]*)(?=[^{;]*\{)|$IDENT""")
        )
        put(
            Language.MARKDOWN,
            Regex("""(?<code>```[\s\S]*?(?:```|$)|`[^`\n]+`)|(?<head>(?m:^#{1,6}[ \t].*$))|(?<emph>\*\*[^*\n]+\*\*|__[^_\n]+__|\*[^*\n]+\*|_[^_\n]+_)|(?<link>!?\[[^\]\n]*]\([^)\n]*\))|(?<tag>(?m:^[ \t]*(?:[-*+]|\d+\.)[ \t]))""")
        )
    }

    private val GROUPS = listOf(
        "cmt" to TokenType.COMMENT, "str" to TokenType.STRING, "ann" to TokenType.ANNOTATION, "num" to TokenType.NUMBER,
        "prop" to TokenType.PROPERTY, "tag" to TokenType.TAG, "var" to TokenType.VARIABLE, "code" to TokenType.CODE,
        "head" to TokenType.HEADING, "emph" to TokenType.EMPHASIS, "link" to TokenType.LINK
    )

    fun highlight(text: String, language: Language): List<HighlightSpan> {
        val regex = patterns[language] ?: return emptyList()
        val keywords = KEYWORDS[language].orEmpty()
        val caseInsensitive = language == Language.SQL
        val typeAware = language in setOf(Language.KOTLIN, Language.JAVA, Language.JAVASCRIPT, Language.RUST, Language.GO, Language.C)
        val result = ArrayList<HighlightSpan>()
        val pattern = regex.toPattern()
        val names = GROUPS.filter { (name, _) -> pattern.pattern().contains("(?<$name>") }
        val hasIdent = pattern.pattern().contains("(?<id>")
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            if (matcher.end() == matcher.start()) continue
            val typed = names.firstOrNull { (name, _) -> matcher.start(name) >= 0 }
            if (typed != null) {
                result += HighlightSpan(matcher.start(typed.first), matcher.end(typed.first), typed.second)
                continue
            }
            if (hasIdent && matcher.start("id") >= 0) {
                val word = matcher.group("id")
                val lookup = if (caseInsensitive) word.lowercase(Locale.ROOT) else word
                when {
                    lookup in keywords -> result += HighlightSpan(matcher.start(), matcher.end(), TokenType.KEYWORD)
                    typeAware && word.first().isUpperCase() && word.any { it.isLowerCase() } ->
                        result += HighlightSpan(matcher.start(), matcher.end(), TokenType.TYPE)
                }
            }
        }
        return result
    }
}
