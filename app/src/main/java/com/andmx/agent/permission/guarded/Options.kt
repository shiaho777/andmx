package com.andmx.agent.permission.guarded

enum class OptionArity { FLAG, VALUE, OPTIONAL }

enum class OptionOrdering { STOP_AT_OPERAND, INTERSPERSED }

enum class UnknownOption { ABORT, CONTINUE }

data class OptionScanPolicy(
    val ordering: OptionOrdering,
    val unknown: UnknownOption,
)

val WRAPPER_SCAN = OptionScanPolicy(OptionOrdering.STOP_AT_OPERAND, UnknownOption.ABORT)
val COMMAND_SCAN = OptionScanPolicy(OptionOrdering.INTERSPERSED, UnknownOption.CONTINUE)

class ParsedOptions(
    val flags: MutableList<String> = mutableListOf(),
    val operands: MutableList<CommandWord> = mutableListOf(),
    var unsupported: Boolean = false,
)

sealed class RuleMatch {
    data class Hit(val id: String) : RuleMatch()
    data object Miss : RuleMatch()
    data object Unsupported : RuleMatch()
}

fun parseOptions(
    words: List<CommandWord>,
    table: Map<String, OptionArity>,
    policy: OptionScanPolicy,
): ParsedOptions {
    val result = ParsedOptions()
    var ended = false
    var i = 0
    while (i < words.size) {
        val word = words[i]
        if (!ended && !word.dynamic && word.value == "--") {
            ended = true
            i++
            continue
        }
        if (ended || word.dynamic || !word.value.startsWith("-") || word.value == "-") {
            result.operands.add(word)
            if (policy.ordering == OptionOrdering.STOP_AT_OPERAND) {
                if (i + 1 < words.size) result.operands.addAll(words.subList(i + 1, words.size))
                break
            }
            i++
            continue
        }
        val long = word.value.startsWith("--")
        val equals = word.value.indexOf('=')
        val names = if (long) {
            listOf(if (equals < 0) word.value else word.value.substring(0, equals))
        } else {
            word.value.substring(1).map { "-$it" }
        }
        var abort = false
        for (n in names.indices) {
            val name = names[n]
            val arity = table[name]
            val attached = if (long) equals >= 0 else n < names.size - 1
            if (arity == null || (arity == OptionArity.FLAG && long && attached)) {
                result.unsupported = true
                if (policy.unknown == UnknownOption.ABORT) {
                    abort = true
                    break
                }
                continue
            }
            result.flags.add(name)
            if (arity == OptionArity.VALUE && !attached) {
                i++
                if (i >= words.size) result.unsupported = true
            }
            if (arity != OptionArity.FLAG) break
        }
        if (abort) return result
        i++
    }
    return result
}

fun optionTable(flags: String, values: String = "", optional: String = ""): Map<String, OptionArity> {
    val table = linkedMapOf<String, OptionArity>()
    for (name in flags.split(Regex("\\s+")).filter { it.isNotEmpty() }) table[name] = OptionArity.FLAG
    for (name in values.split(Regex("\\s+")).filter { it.isNotEmpty() }) table[name] = OptionArity.VALUE
    for (name in optional.split(Regex("\\s+")).filter { it.isNotEmpty() }) table[name] = OptionArity.OPTIONAL
    return table
}

fun enabled(flags: List<String>, yes: List<String>, no: List<String> = emptyList()): Boolean {
    var result = false
    for (flag in flags) {
        if (flag in yes) result = true
        if (flag in no) result = false
    }
    return result
}

fun settleRule(rule: String?, parsed: ParsedOptions): RuleMatch {
    if (!rule.isNullOrEmpty()) return RuleMatch.Hit(rule)
    return if (parsed.unsupported) RuleMatch.Unsupported else RuleMatch.Miss
}
