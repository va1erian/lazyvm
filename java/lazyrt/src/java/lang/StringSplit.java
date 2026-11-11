package java.lang;

import java.util.ArrayList;

/**
 * String.split without a Pattern when the regex is one plain character, or a backslash and one
 * ASCII punctuation character: what games split their data and save lines on, every tick. A
 * compiled Pattern costs thousands of interpreted calls; this, a scan (plan S11-06 part 4).
 * Results are Pattern.split's: at most limit parts when limit > 0, trailing empty strings dropped
 * when limit is 0, and the string itself when the separator does not occur.
 */
final class StringSplit {
    /** The characters a one-character regex treats specially. */
    private static final String META = ".$|()[{^?*+\\";

    private StringSplit() {
    }

    /** The parts, or null when the regex is not a plain separator (Pattern decides then). */
    static String[] literal(String s, String regex, int limit) {
        int sep = separator(regex);
        if (sep < 0) {
            return null;
        }
        ArrayList<String> parts = new ArrayList<String>();
        int start = 0;
        for (int at = s.indexOf(sep); at >= 0; at = s.indexOf(sep, start)) {
            if (limit > 0 && parts.size() == limit - 1) {
                break;
            }
            parts.add(s.substring(start, at));
            start = at + 1;
        }
        if (parts.isEmpty()) {
            return new String[] {s};
        }
        parts.add(s.substring(start));
        int size = parts.size();
        if (limit == 0) {
            while (size > 0 && parts.get(size - 1).isEmpty()) {
                size--;
            }
        }
        return parts.subList(0, size).toArray(new String[size]);
    }

    /** The character `regex` matches literally, or -1. */
    private static int separator(String regex) {
        if (regex.length() == 1) {
            char c = regex.charAt(0);
            return META.indexOf(c) < 0 && !Character.isSurrogate(c) ? c : -1;
        }
        if (regex.length() == 2 && regex.charAt(0) == '\\') {
            char c = regex.charAt(1);
            boolean punctuation = c < 128 && !Character.isLetterOrDigit(c);
            return punctuation ? c : -1;
        }
        return -1;
    }
}
