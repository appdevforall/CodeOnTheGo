package com.itsaky.androidide.java.lang;

import java.util.ArrayList;
import java.util.List;

/**
* A utility class uses Java 14+ API that's not supported in old Android runtime
* @author Mutwakil Suliman (Wadamzmail)
*/

public class Str {

    public static String stripIndent(String str) {
        int length = str.length();

        if (length == 0) {
            return "";
        }

        char lastChar = str.charAt(length - 1);
        boolean optOut = lastChar == '\n' || lastChar == '\r';

        List<String> lines = lines(str);
        final int outdent = optOut ? 0 : outdent(lines);

        StringBuilder result = new StringBuilder(str.length());

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            int firstNonWhitespace = indexOfNonWhitespace(line);
            int lastNonWhitespace = lastIndexOfNonWhitespace(line);

            int incidentalWhitespace = Math.min(
                    outdent,
                    firstNonWhitespace
            );

            if (firstNonWhitespace <= lastNonWhitespace) {
                result.append(
                        line,
                        incidentalWhitespace,
                        lastNonWhitespace
                );
            }

            if (i + 1 < lines.size()) {
                result.append('\n');
            }
        }

        if (optOut) {
            result.append('\n');
        }

        return result.toString();
    }

    private static int outdent(List<String> lines) {
        int outdent = Integer.MAX_VALUE;

        for (String line : lines) {
            int leadingWhitespace = indexOfNonWhitespace(line);

            if (leadingWhitespace != line.length()) {
                outdent = Math.min(outdent, leadingWhitespace);
            }
        }

        String lastLine = lines.get(lines.size() - 1);

        if (isBlank(lastLine)) {
            outdent = Math.min(outdent, lastLine.length());
        }

        return outdent;
    }

    private static int indexOfNonWhitespace(String str) {
        int length = str.length();
        int index = 0;

        while (index < length) {
            int codePoint = str.codePointAt(index);

            if (codePoint != ' '
                    && codePoint != '\t'
                    && !Character.isWhitespace(codePoint)) {
                break;
            }

            index += Character.charCount(codePoint);
        }

        return index;
    }

    private static int lastIndexOfNonWhitespace(String str) {
        int index = str.length();

        while (index > 0) {
            int codePoint = str.codePointBefore(index);

            if (codePoint != ' '
                    && codePoint != '\t'
                    && !Character.isWhitespace(codePoint)) {
                break;
            }

            index -= Character.charCount(codePoint);
        }

        return index;
    }

    private static boolean isBlank(String str) {
        return indexOfNonWhitespace(str) == str.length();
    }

    private static List<String> lines(String str) {
        List<String> lines = new ArrayList<String>();

        int length = str.length();
        int start = 0;

        for (int i = 0; i < length; i++) {
            char ch = str.charAt(i);

            if (ch == '\n') {
                lines.add(str.substring(start, i));
                start = i + 1;
            } else if (ch == '\r') {
                lines.add(str.substring(start, i));

                if (i + 1 < length && str.charAt(i + 1) == '\n') {
                    i++;
                }

                start = i + 1;
            }
        }

        if (start < length) {
            lines.add(str.substring(start));
        } else if (length > 0
                && (str.charAt(length - 1) == '\n'
                || str.charAt(length - 1) == '\r')) {
            lines.add("");
        }

        return lines;
    }

    public static String translateEscapes(String str) {
        if (str.length() == 0) {
            return "";
        }

        char[] chars = str.toCharArray();
        int length = chars.length;
        int from = 0;
        int to = 0;

        while (from < length) {
            char ch = chars[from++];

            if (ch == '\\') {
                ch = from < length ? chars[from++] : '\0';

                switch (ch) {
                    case 'b':
                        ch = '\b';
                        break;

                    case 'f':
                        ch = '\f';
                        break;

                    case 'n':
                        ch = '\n';
                        break;

                    case 'r':
                        ch = '\r';
                        break;

                    case 's':
                        ch = ' ';
                        break;

                    case 't':
                        ch = '\t';
                        break;

                    case '\'':
                    case '"':
                    case '\\':
                        break;

                    case '0':
                    case '1':
                    case '2':
                    case '3':
                    case '4':
                    case '5':
                    case '6':
                    case '7':
                        int limit = Math.min(
                                from + (ch <= '3' ? 2 : 1),
                                length
                        );

                        int code = ch - '0';

                        while (from < limit) {
                            ch = chars[from];

                            if (ch < '0' || ch > '7') {
                                break;
                            }

                            from++;
                            code = (code << 3) | (ch - '0');
                        }

                        ch = (char) code;
                        break;

                    case '\n':
                        continue;

                    case '\r':
                        if (from < length && chars[from] == '\n') {
                            from++;
                        }
                        continue;

                    default:
                        String msg = String.format(
                                "Invalid escape sequence: \\%c \\\\u%04X",
                                ch,
                                (int) ch
                        );

                        throw new IllegalArgumentException(msg);
                }
            }

            chars[to++] = ch;
        }

        return new String(chars, 0, to);
    }
}