package dev.droidprobe

import java.util.concurrent.ConcurrentHashMap

class CommandRegistry {
    private val commands = ConcurrentHashMap<String, DebugCommand>()

    /** For probe modules; may use reserved prefixes. */
    fun registerBuiltIn(command: DebugCommand) {
        check(commands.putIfAbsent(command.name, command) == null) { "Command ${command.name} is already registered" }
    }

    /** For app commands; reserved prefixes and duplicates are rejected, which fails the setup. */
    fun register(command: DebugCommand) {
        require(RESERVED_PREFIXES.none { command.name.startsWith(it) }) {
            "Command ${command.name} uses a reserved prefix (${RESERVED_PREFIXES.joinToString()})"
        }
        require(command.name.isNotBlank()) { "Command name must not be blank" }
        registerBuiltIn(command)
    }

    internal fun unregister(name: String) {
        commands.remove(name)
    }

    operator fun get(name: String): DebugCommand? = commands[name]

    fun all(): List<DebugCommand> = commands.values.sortedBy { it.name }

    internal fun suggest(name: String): String? =
        commands.keys.map { it to distance(it, name) }.filter { it.second <= 3 }.minByOrNull { it.second }?.first

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    companion object {
        val RESERVED_PREFIXES = listOf("probe.", "network.", "events.", "lifecycle.")
    }
}
