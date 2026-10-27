package java.lang;

/** Searching and comparing on raw UTF-16 arrays, shared by String's methods. */
final class StringSearch {
    private StringSearch() {
    }

    static int codePointCount(char[] v, int begin, int end) {
        int n = end - begin;
        for (int i = begin; i < end - 1; i++) {
            if (Character.isHighSurrogate(v[i]) && Character.isLowSurrogate(v[i + 1])) {
                n--;
                i++;
            }
        }
        return n;
    }

    static int offsetByCodePoints(char[] v, int index, int codePointOffset) {
        int i = index;
        for (int k = 0; k < codePointOffset; k++) {
            if (i >= v.length) {
                throw new IndexOutOfBoundsException();
            }
            boolean pair = Character.isHighSurrogate(v[i]) && i + 1 < v.length
                    && Character.isLowSurrogate(v[i + 1]);
            i += pair ? 2 : 1;
        }
        for (int k = 0; k > codePointOffset; k--) {
            if (i <= 0) {
                throw new IndexOutOfBoundsException();
            }
            boolean pair = i >= 2 && Character.isLowSurrogate(v[i - 1])
                    && Character.isHighSurrogate(v[i - 2]);
            i -= pair ? 2 : 1;
        }
        return i;
    }

    static int compareIgnoreCase(char[] v, char[] w) {
        int n = Math.min(v.length, w.length);
        for (int i = 0; i < n; i++) {
            char a = v[i];
            char b = w[i];
            if (a != b) {
                a = Character.toUpperCase(a);
                b = Character.toUpperCase(b);
                if (a != b) {
                    a = Character.toLowerCase(a);
                    b = Character.toLowerCase(b);
                    if (a != b) {
                        return a - b;
                    }
                }
            }
        }
        return v.length - w.length;
    }

    static boolean regionMatches(char[] v, boolean ignoreCase, int toffset, char[] w, int ooffset,
            int len) {
        if (ooffset < 0 || toffset < 0 || (long) toffset + len > v.length
                || (long) ooffset + len > w.length) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char a = v[toffset + i];
            char b = w[ooffset + i];
            if (a == b) {
                continue;
            }
            if (!ignoreCase) {
                return false;
            }
            char ua = Character.toUpperCase(a);
            char ub = Character.toUpperCase(b);
            if (ua != ub && Character.toLowerCase(ua) != Character.toLowerCase(ub)) {
                return false;
            }
        }
        return true;
    }

    /** The code point `ch` as a char (or a high/low pair) for searching; null if invalid. */
    private static char[] unit(int ch) {
        if (ch < Character.MIN_SUPPLEMENTARY_CODE_POINT) {
            return new char[] {(char) ch};
        }
        if (!Character.isValidCodePoint(ch)) {
            return null;
        }
        return new char[] {Character.highSurrogate(ch), Character.lowSurrogate(ch)};
    }

    static int indexOf(char[] v, int ch, int from) {
        char[] u = unit(ch);
        return u == null ? -1 : indexOf(v, u, from);
    }

    static int lastIndexOf(char[] v, int ch, int from) {
        char[] u = unit(ch);
        return u == null ? -1 : lastIndexOf(v, u, from);
    }

    static int indexOf(char[] v, char[] s, int from) {
        return indexOf(v, v.length, s, from);
    }

    /** The first match of `s` in `v[0, len)` from `from`: a builder searches its used part in place. */
    static int indexOf(char[] v, int len, char[] s, int from) {
        if (from < 0) {
            from = 0;
        }
        if (s.length == 0) {
            return Math.min(from, len);
        }
        // The first char is scanned for inline; only a hit is compared in full.
        char first = s[0];
        for (int i = from, last = len - s.length; i <= last; i++) {
            if (v[i] == first && matchesAt(v, len, s, i)) {
                return i;
            }
        }
        return -1;
    }

    static int lastIndexOf(char[] v, char[] s, int from) {
        return lastIndexOf(v, v.length, s, from);
    }

    static int lastIndexOf(char[] v, int len, char[] s, int from) {
        int i = Math.min(from, len - s.length);
        if (s.length == 0) {
            return i < 0 ? -1 : i;
        }
        for (char first = s[0]; i >= 0; i--) {
            if (v[i] == first && matchesAt(v, len, s, i)) {
                return i;
            }
        }
        return -1;
    }

    static boolean matchesAt(char[] v, char[] s, int at) {
        return matchesAt(v, v.length, s, at);
    }

    private static boolean matchesAt(char[] v, int len, char[] s, int at) {
        if (at < 0 || at > len - s.length) {
            return false;
        }
        for (int i = 0; i < s.length; i++) {
            if (v[at + i] != s[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * String.split, with a fast path for patterns that match one literal string; others go to
     * StringRegex.
     */
    static String[] split(String s, String regex, int limit) {
        String sep = literal(regex);
        if (sep == null) {
            return StringRegex.split(s, regex, limit);
        }
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        int from = 0;
        int at;
        while ((limit <= 0 || parts.size() < limit - 1) && (at = s.indexOf(sep, from)) >= 0) {
            parts.add(s.substring(from, at));
            from = at + sep.length();
        }
        if (parts.isEmpty()) {
            return new String[] {s};
        }
        parts.add(s.substring(from));
        int n = parts.size();
        while (limit == 0 && n > 0 && parts.get(n - 1).isEmpty()) {
            n--;
        }
        return parts.subList(0, n).toArray(new String[n]);
    }

    /** The text a pattern matches if it is a plain string or one escaped symbol, else null. */
    private static String literal(String regex) {
        if (regex.length() == 2 && regex.charAt(0) == '\\' && !Character.isLetterOrDigit(regex.charAt(1))) {
            return regex.substring(1);
        }
        for (int i = 0; i < regex.length(); i++) {
            if (".$|()[{^?*+\\".indexOf(regex.charAt(i)) >= 0) {
                return null;
            }
        }
        return regex.isEmpty() ? null : regex;
    }

    /** String.replace(CharSequence, CharSequence): every occurrence, left to right. */
    static String replace(String s, String target, String replacement) {
        StringBuilder sb = new StringBuilder();
        if (target.isEmpty()) {
            for (int i = 0; i < s.length(); i++) {
                sb.append(replacement).append(s.charAt(i));
            }
            return sb.append(replacement).toString();
        }
        int from = 0;
        int at;
        while ((at = s.indexOf(target, from)) >= 0) {
            sb.append(s, from, at).append(replacement);
            from = at + target.length();
        }
        return sb.append(s, from, s.length()).toString();
    }

    /** The chars of code points codePoints[offset, offset + count). */
    static char[] fromCodePoints(int[] codePoints, int offset, int count) {
        int n = 0;
        for (int i = offset; i < offset + count; i++) {
            int cp = codePoints[i];
            if (!Character.isValidCodePoint(cp)) {
                throw new IllegalArgumentException(Integer.toString(cp));
            }
            n += Character.charCount(cp);
        }
        char[] value = new char[n];
        int pos = 0;
        for (int i = offset; i < offset + count; i++) {
            pos += Character.toChars(codePoints[i], value, pos);
        }
        return value;
    }
}
