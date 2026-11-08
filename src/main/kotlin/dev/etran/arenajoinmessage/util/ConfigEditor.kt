package dev.etran.arenajoinmessage.util

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * Changes single values in a YAML file *in place* so every comment survives.
 * (Bukkit's saveConfig() would drop the comments on most server versions.)
 *
 * Supports replacing an existing scalar value and adding a new scalar key to an existing section.
 */
object ConfigEditor {

    enum class Result { UPDATED, ADDED, NOT_FOUND, NOT_SCALAR }

    /** YAML literal for [value]: numbers and booleans plain, everything else double-quoted. */
    fun literal(value: Any): String = when (value) {
        is Boolean, is Int, is Long, is Double, is Float -> value.toString()
        else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    fun set(file: File, path: String, yamlLiteral: String): Result {
        val lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).toMutableList()
        val result = apply(lines, path, yamlLiteral)
        if (result == Result.UPDATED || result == Result.ADDED) {
            Files.write(file.toPath(), (lines.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8))
        }
        return result
    }

    // --- parsing -------------------------------------------------------------------------------

    private class KeyLine(val indent: Int, val key: String, val colonIndex: Int)

    private fun parseKey(line: String): KeyLine? {
        val trimmed = line.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed == "-" || trimmed.startsWith("- ")) return null
        val indent = line.length - trimmed.length
        val key: String
        val afterKey: Int
        val quote = trimmed[0]
        if (quote == '"' || quote == '\'') {
            var i = 1
            while (i < trimmed.length && trimmed[i] != quote) {
                if (quote == '"' && trimmed[i] == '\\') i++
                i++
            }
            if (i >= trimmed.length) return null
            key = trimmed.substring(1, i)
            afterKey = indent + i + 1
        } else {
            val colon = trimmed.indexOf(':')
            if (colon <= 0) return null
            key = trimmed.substring(0, colon).trim()
            afterKey = indent + colon
        }
        val colonIndex = line.indexOf(':', afterKey)
        if (colonIndex < 0 || line.substring(afterKey, colonIndex).isNotBlank()) return null
        return KeyLine(indent, key, colonIndex)
    }

    /** Index of the first character after the value starting at [start] (quote aware). */
    private fun valueEnd(line: String, start: Int): Int {
        val first = line[start]
        if (first == '"' || first == '\'') {
            var i = start + 1
            while (i < line.length && line[i] != first) {
                if (first == '"' && line[i] == '\\') i++
                i++
            }
            return minOf(i + 1, line.length)
        }
        val comment = line.indexOf(" #", start)
        val end = if (comment >= 0) comment else line.length
        var e = end
        while (e > start && line[e - 1] == ' ') e--
        return e
    }

    private fun apply(lines: MutableList<String>, path: String, literal: String): Result {
        val wanted = path.split('.')
        val stack = ArrayList<Pair<Int, String>>() // indent to key of enclosing sections
        val sectionLines = HashMap<String, Int>() // section path -> line index

        for (index in lines.indices) {
            val kl = parseKey(lines[index]) ?: continue
            while (stack.isNotEmpty() && stack.last().first >= kl.indent) stack.removeAt(stack.size - 1)
            val fullPath = (stack.map { it.second } + kl.key).joinToString(".")

            val rest = lines[index].substring(kl.colonIndex + 1)
            val body = rest.trimStart()
            val isSection = body.isEmpty() || body.startsWith("#")
            if (isSection) {
                stack.add(kl.indent to kl.key)
                sectionLines[fullPath] = index
            }

            if (fullPath == path) {
                if (isSection || body.startsWith("[") || body.startsWith("{") || body.startsWith("|") || body.startsWith(">")) {
                    return Result.NOT_SCALAR
                }
                val valueStart = kl.colonIndex + 1 + (rest.length - body.length)
                val end = valueEnd(lines[index], valueStart)
                lines[index] = lines[index].substring(0, kl.colonIndex + 1) + " " + literal + lines[index].substring(end)
                return Result.UPDATED
            }
        }

        // Not present: add it to an existing parent section (or the file root).
        val key = wanted.last()
        val parentPath = wanted.dropLast(1).joinToString(".")
        val keyText = if (Regex("[A-Za-z_][A-Za-z0-9_-]*").matches(key)) key else "\"$key\""

        if (parentPath.isEmpty()) {
            lines.add("$keyText: $literal")
            return Result.ADDED
        }
        val header = sectionLines[parentPath] ?: return Result.NOT_FOUND
        val headerIndent = lines[header].length - lines[header].trimStart().length

        var childIndent = headerIndent + 2
        var end = lines.size
        var foundChild = false
        for (i in header + 1 until lines.size) {
            val trimmed = lines[i].trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = lines[i].length - trimmed.length
            if (indent <= headerIndent) { end = i; break }
            if (!foundChild) { childIndent = indent; foundChild = true }
        }
        var insertAt = end
        while (insertAt > header + 1) {
            val trimmed = lines[insertAt - 1].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) insertAt-- else break
        }
        lines.add(insertAt, " ".repeat(childIndent) + "$keyText: $literal")
        return Result.ADDED
    }
}
