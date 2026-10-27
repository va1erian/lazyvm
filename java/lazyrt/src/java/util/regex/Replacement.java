package java.util.regex;

/** Expands a replacement string ($n, ${name}, backslash escapes) against the current match. */
final class Replacement {
    private Replacement() {
    }

    static String expand(Matcher m, String replacement) {
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        int len = replacement.length();
        while (cursor < len) {
            char c = replacement.charAt(cursor);
            if (c == '\\') {
                cursor++;
                if (cursor == len) {
                    throw new IllegalArgumentException("character to be escaped is missing");
                }
                result.append(replacement.charAt(cursor));
                cursor++;
            } else if (c == '$') {
                cursor = reference(m, replacement, cursor + 1, result);
            } else {
                result.append(c);
                cursor++;
            }
        }
        return result.toString();
    }

    /** Handles the text after a '$' and returns the index after the reference. */
    private static int reference(Matcher m, String s, int cursor, StringBuilder out) {
        int len = s.length();
        if (cursor == len) {
            throw new IllegalArgumentException("Illegal group reference: group index is missing");
        }
        char next = s.charAt(cursor);
        int ref;
        if (next == '{') {
            cursor++;
            StringBuilder name = new StringBuilder();
            while (cursor < len) {
                next = s.charAt(cursor);
                if (isNameChar(next)) {
                    name.append(next);
                    cursor++;
                } else {
                    break;
                }
            }
            if (name.length() == 0) {
                throw new IllegalArgumentException("named capturing group has 0 length name");
            }
            if (next != '}') {
                throw new IllegalArgumentException("named capturing group is missing trailing '}'");
            }
            String groupName = name.toString();
            if (name.charAt(0) >= '0' && name.charAt(0) <= '9') {
                throw new IllegalArgumentException(
                    "capturing group name {" + groupName + "} starts with digit character");
            }
            ref = m.parentPattern.groupNumber(groupName);
            if (ref < 0) {
                throw new IllegalArgumentException("No group with name {" + groupName + "}");
            }
            cursor++;
        } else {
            ref = next - '0';
            if (ref < 0 || ref > 9) {
                throw new IllegalArgumentException("Illegal group reference");
            }
            cursor++;
            while (cursor < len) {
                int digit = s.charAt(cursor) - '0';
                if (digit < 0 || digit > 9) {
                    break;
                }
                int wider = ref * 10 + digit;
                if (m.groupCount() < wider) {
                    break;
                }
                ref = wider;
                cursor++;
            }
        }
        String captured = m.group(ref);
        if (captured != null) {
            out.append(captured);
        }
        return cursor;
    }

    private static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
