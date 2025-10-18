package com.itsaky.androidide.agent.tool.shell

object ShellCommandParser {

    fun tokenize(command: String): List<String> {
        if (command.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var isEscaped = false

        for (ch in command) {
            when {
                isEscaped -> {
                    current.append(ch)
                    isEscaped = false
                }

                ch == '\\' -> {
                    isEscaped = true
                }

                ch == '\'' && !inDoubleQuote -> {
                    inSingleQuote = !inSingleQuote
                    if (!inSingleQuote) {
                        // closing single quote, nothing to append
                    }
                }

                ch == '"' && !inSingleQuote -> {
                    inDoubleQuote = !inDoubleQuote
                    if (!inDoubleQuote) {
                        // closing double quote
                    }
                }

                ch.isWhitespace() && !inSingleQuote && !inDoubleQuote -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }

                else -> current.append(ch)
            }
        }

        if (isEscaped) {
            current.append('\\')
        }

        if (current.isNotEmpty()) {
            tokens += current.toString()
        }

        return tokens
    }

    fun parse(tokens: List<String>, original: String): ParsedCommand {
        if (tokens.isEmpty()) {
            return ParsedCommand.Unknown(original)
        }
        val program = tokens.first().lowercase()
        val args = tokens.drop(1)
        return when (program) {
            "cat", "tail", "head" -> parseReadCommand(program, args, original)
            "ls" -> parseListCommand(args, original)
            "rg", "ripgrep", "ag", "ack" -> parseSearchCommand(args, original)
            "grep", "egrep", "fgrep" -> parseSearchCommand(args, original)
            else -> ParsedCommand.Unknown(original)
        }
    }

    private fun parseReadCommand(
        program: String,
        args: List<String>,
        original: String
    ): ParsedCommand {
        val files = args.filterNot { it.startsWith("-") }
        if (files.isEmpty()) {
            return ParsedCommand.Unknown(original)
        }
        return ParsedCommand.Read(original, files)
    }

    private fun parseListCommand(
        args: List<String>,
        original: String
    ): ParsedCommand {
        val target = args.firstOrNull { !it.startsWith("-") } ?: "."
        return ParsedCommand.ListFiles(original, target)
    }

    private fun parseSearchCommand(
        args: List<String>,
        original: String
    ): ParsedCommand {
        var query: String? = null
        var path: String? = null
        for (arg in args) {
            if (arg.startsWith("-")) continue
            if (query == null) {
                query = arg
            } else {
                path = arg
                break
            }
        }
        return if (query != null) {
            ParsedCommand.Search(original, query, path)
        } else {
            ParsedCommand.Unknown(original)
        }
    }
}
