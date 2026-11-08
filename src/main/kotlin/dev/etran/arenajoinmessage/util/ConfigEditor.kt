package dev.etran.arenajoinmessage.util

import java.io.File
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Changes single values in a YAML file *in place* so every comment survives.
 * (Bukkit's saveConfig() would drop the comments on most server versions.)
 *
 * Supports replacing an existing scalar value and adding a new scalar key to an existing section.
 */
object ConfigEditor {

    enum class Result { UPDATED, ADDED, NOT_FOUND, NOT_SCALAR }

    /**
     * YAML literal for [value]: numbers and booleans plain, everything else double-quoted.
     * Non-ASCII characters are written as \u escapes so the file reads the same on every server
     * version and in every editor, whatever charset the old Bukkit versions assume.
     */
    fun literal(value: Any): String = when (value) {
        is Boolean, is Int, is Long -> value.toString()
        is Double, is Float -> BigDecimal(value.toString()).toPlainString()
        else -> {
            val out = StringBuilder("\"")
            value.toString().codePoints().forEach { cp ->
                when {
                    cp == '\\'.code -> out.append("\\\\")
                    cp == '"'.code -> out.append("\\\"")
                    cp == '\n'.code || cp == '\r'.code || cp == '\t'.code -> out.append(' ')
                    cp in 32..126 -> out.append(cp.toChar())
                    cp < 0x10000 -> out.append(String.format("\\u%04X", cp))
                    else -> out.append(String.format("\\U%08X", cp))
                }
            }
            out.append('"').toString()
        }
    }

    fun set(file: File, path: String, yamlLiteral: String): Result {
        val lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).toMutableList()
        // A byte order mark (Windows editors) would glue itself to the first key and hide it from the search.
        if (lines.isNotEmpty() && lines[0].startsWith("\uFEFF")) lines[0] = lines[0].substring(1)
        val result = apply(lines, path, yamlLiteral)
        if (result == Result.UPDATED || result == Result.ADDED) writeAtomically(file, lines.joinToString("\n") + "\n")
        return result
    }

    /** Writes to a temp file first so a crash half way can never leave a truncated file behind. */
    fun writeAtomically(file: File, content: String) {
        val target = file.toPath()
        val temp = target.resolveSibling(file.name + ".tmp")
        Files.write(temp, content.toByteArray(StandardCharsets.UTF_8))
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
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

    /** One key found in the file: its line and whether it opens a (block) section. */
    private class Entry(val line: Int, val isSection: Boolean, val body: String)

    /** Maps every dotted key path in the file to where it is. Keys inside list items are ignored. */
    private fun scan(lines: List<String>): Map<String, Entry> {
        val result = HashMap<String, Entry>()
        val stack = ArrayList<Pair<Int, String>>() // indent to key of enclosing sections
        var listItemIndent = -1 // inside a "- " item: deeper keys belong to the item, not to a section

        for (index in lines.indices) {
            val line = lines[index]
            val trimmed = line.trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = line.length - trimmed.length
            if (trimmed == "-" || trimmed.startsWith("- ")) {
                listItemIndent = indent
                continue
            }
            if (listItemIndent >= 0) {
                if (indent > listItemIndent) continue // e.g. "  - name: x\n    other: y"
                listItemIndent = -1
            }
            val kl = parseKey(line) ?: continue
            while (stack.isNotEmpty() && stack.last().first >= kl.indent) stack.removeAt(stack.size - 1)
            val fullPath = (stack.map { it.second } + kl.key).joinToString(".")

            val body = line.substring(kl.colonIndex + 1).trimStart()
            // "key:" with nothing after it is a section, unless the next real line is a "- " item (a block list).
            val isSection = (body.isEmpty() || body.startsWith("#")) && !startsBlockList(lines, index, kl.indent)
            if (isSection) stack.add(kl.indent to kl.key)
            result.putIfAbsent(fullPath, Entry(index, isSection, body))
        }
        return result
    }

    private fun startsBlockList(lines: List<String>, keyLine: Int, keyIndent: Int): Boolean {
        for (i in keyLine + 1 until lines.size) {
            val trimmed = lines[i].trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = lines[i].length - trimmed.length
            return indent >= keyIndent && (trimmed == "-" || trimmed.startsWith("- "))
        }
        return false
    }

    private fun keyText(key: String): String =
        if (Regex("[A-Za-z_][A-Za-z0-9_-]*").matches(key)) key
        else "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun apply(lines: MutableList<String>, path: String, literal: String): Result {
        val wanted = path.split('.')
        if (wanted.any { it.isEmpty() }) return Result.NOT_FOUND
        val entries = scan(lines)

        entries[path]?.let { entry ->
            val body = entry.body
            if (entry.isSection || body.isEmpty() || body.startsWith("#") || body.startsWith("[") || body.startsWith("{") || body.startsWith("|") || body.startsWith(">")) {
                return Result.NOT_SCALAR
            }
            val line = lines[entry.line]
            val colon = parseKey(line)!!.colonIndex
            val valueStart = line.length - body.length
            val end = valueEnd(line, valueStart)
            lines[entry.line] = line.substring(0, colon + 1) + " " + literal + line.substring(end)
            return Result.UPDATED
        }

        // Not present. Find the deepest existing parent section, then create whatever is missing below it.
        var existing = wanted.size - 1
        while (existing > 0) {
            val entry = entries[wanted.subList(0, existing).joinToString(".")]
            if (entry != null) {
                if (!entry.isSection) return Result.NOT_SCALAR // e.g. "enabled.x" while enabled is a value or a list
                break
            }
            existing--
        }

        var parentLine = if (existing == 0) -1 else entries.getValue(wanted.subList(0, existing).joinToString(".")).line
        for (depth in existing until wanted.size) {
            val isLast = depth == wanted.size - 1
            val text = keyText(wanted[depth]) + ":" + if (isLast) " $literal" else ""
            parentLine = insertChild(lines, parentLine, text)
        }
        return Result.ADDED
    }

    /** Adds [text] as the last child of the section starting at [header] (-1 = file root). Returns its line. */
    private fun insertChild(lines: MutableList<String>, header: Int, text: String): Int {
        if (header < 0) {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            lines.add(text)
            return lines.size - 1
        }
        val headerIndent = lines[header].length - lines[header].trimStart().length
        var childIndent = headerIndent + 2
        var end = lines.size
        var foundChild = false
        for (i in header + 1 until lines.size) {
            val trimmed = lines[i].trimStart()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val indent = lines[i].length - trimmed.length
            // "- item" lines may sit at the same indent as their key; they still belong to this section
            if (indent < headerIndent || (indent == headerIndent && !trimmed.startsWith("-"))) { end = i; break }
            if (!foundChild && !trimmed.startsWith("-")) { childIndent = indent; foundChild = true }
        }
        var insertAt = end
        while (insertAt > header + 1) {
            val trimmed = lines[insertAt - 1].trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) insertAt-- else break
        }
        lines.add(insertAt, " ".repeat(childIndent) + text)
        return insertAt
    }
}
