package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A shell-integration mark: an {@code OSC 133} sequence a shell prints to say where a prompt, a
 * command and its output begin, and that a command finished with an exit code. The terminal does
 * not show it.
 *
 * <p>{@code ESC ] 133 ; D ; 130 ; key=value BEL} is {@link Kind#COMMAND_FINISHED} with exit code 130
 * and one option.
 */
public final class ShellIntegrationMark {

    public enum Kind {
        PROMPT_START('A'),
        COMMAND_START('B'),
        OUTPUT_START('C'),
        COMMAND_FINISHED('D');

        private final char code;

        Kind(char code) {
            this.code = code;
        }

        @Nullable
        static Kind of(@NonNull String code) {
            if (code.length() != 1) return null;
            for (Kind kind : values()) {
                if (kind.code == code.charAt(0)) return kind;
            }
            return null;
        }
    }

    @NonNull
    public final Kind kind;

    /** The exit code a {@link Kind#COMMAND_FINISHED} mark carries, or null when it has none. */
    @Nullable
    public final Integer exitCode;

    /** The {@code key=value} options after the kind and exit code. */
    @NonNull
    public final Map<String, String> options;

    public ShellIntegrationMark(@NonNull Kind kind, @Nullable Integer exitCode, @NonNull Map<String, String> options) {
        this.kind = kind;
        this.exitCode = exitCode;
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    /**
     * Parses the text after {@code 133;}, e.g. {@code "D;130;key=value"}.
     *
     * @return the mark, or null when the text names no known kind.
     */
    @Nullable
    public static ShellIntegrationMark parse(@NonNull String text) {
        String[] fields = text.split(";", -1);
        Kind kind = Kind.of(fields[0]);
        if (kind == null) return null;

        Integer exitCode = null;
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < fields.length; i++) {
            String field = fields[i];
            int equals = field.indexOf('=');
            if (equals > 0) {
                options.put(field.substring(0, equals), field.substring(equals + 1));
            } else if (i == 1 && kind == Kind.COMMAND_FINISHED) {
                exitCode = parseExitCode(field);
            }
        }
        return new ShellIntegrationMark(kind, exitCode, options);
    }

    @Nullable
    private static Integer parseExitCode(String field) {
        try {
            return Integer.parseInt(field);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShellIntegrationMark)) return false;
        ShellIntegrationMark that = (ShellIntegrationMark) o;
        return kind == that.kind && Objects.equals(exitCode, that.exitCode) && options.equals(that.options);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, exitCode, options);
    }

    @NonNull
    @Override
    public String toString() {
        return "ShellIntegrationMark{" + kind + ", exitCode=" + exitCode + ", options=" + options + "}";
    }
}
