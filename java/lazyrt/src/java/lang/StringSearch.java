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
        if (from < 0) {
            from = 0;
        }
        if (s.length == 0) {
            return Math.min(from, v.length);
        }
        for (int i = from; i <= v.length - s.length; i++) {
            if (matchesAt(v, s, i)) {
                return i;
            }
        }
        return -1;
    }

    static int lastIndexOf(char[] v, char[] s, int from) {
        for (int i = Math.min(from, v.length - s.length); i >= 0; i--) {
            if (matchesAt(v, s, i)) {
                return i;
            }
        }
        return -1;
    }

    static boolean matchesAt(char[] v, char[] s, int at) {
        if (at < 0 || at > v.length - s.length) {
            return false;
        }
        for (int i = 0; i < s.length; i++) {
            if (v[at + i] != s[i]) {
                return false;
            }
        }
        return true;
    }
}
