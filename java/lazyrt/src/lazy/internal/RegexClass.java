package lazy.internal;

import java.util.ArrayList;
import java.util.List;
import lazy.internal.RegexNodes.CharTest;

/** Single-char tests for {@link Regex}: literals, `.`, escapes and bracket classes. */
final class RegexClass {
    private RegexClass() {
    }

    /** The char both cases of `c` fold to, for case-insensitive comparison. */
    static char fold(char c) {
        return Character.toLowerCase(Character.toUpperCase(c));
    }

    static CharTest literal(final char c, boolean ignoreCase) {
        if (ignoreCase) {
            final char f = fold(c);
            return new CharTest() {
                boolean test(char x) {
                    return x == c || fold(x) == f;
                }
            };
        }
        return new CharTest() {
            boolean test(char x) {
                return x == c;
            }
        };
    }

    /** `.` without DOTALL: anything but a line terminator. */
    static CharTest dot() {
        return new CharTest() {
            boolean test(char x) {
                return !Regex.isLineTerminator(x);
            }
        };
    }

    /** `\d \s \w` (ASCII, as in Java without UNICODE_CHARACTER_CLASS) and their negations. */
    private static CharTest predefined(final char kind) {
        return new CharTest() {
            boolean test(char x) {
                boolean in;
                switch (Character.toLowerCase(kind)) {
                    case 'd':
                        in = x >= '0' && x <= '9';
                        break;
                    case 's':
                        in = x == ' ' || (x >= '\t' && x <= '\r');
                        break;
                    default:
                        in = (x >= 'a' && x <= 'z') || (x >= 'A' && x <= 'Z') || (x >= '0' && x <= '9') || x == '_';
                }
                return in != Character.isUpperCase(kind);
            }
        };
    }

    /** An escape at the parser's position: a test for a class escape, else the char. */
    static CharTest escape(Regex r) {
        Object e = escapeItem(r);
        return e instanceof CharTest ? (CharTest) e : literal((Character) e, r.ignoreCase());
    }

    /** After `\`: a CharTest for `\d`-like classes, else the escaped Character. */
    private static Object escapeItem(Regex r) {
        r.next();
        char c = r.next();
        switch (c) {
            case 'd': case 'D': case 's': case 'S': case 'w': case 'W':
                return predefined(c);
            case 't':
                return '\t';
            case 'n':
                return '\n';
            case 'r':
                return '\r';
            case 'f':
                return '\f';
            case 'a':
                return '\u0007';
            case 'e':
                return '\u001B';
            case '0':
                return octal(r);
            case 'x':
                return (char) hex(r, 2);
            case 'u':
                return (char) hex(r, 4);
            case 'c':
                return (char) (r.next() ^ 64);
            default:
                break;
        }
        if (c >= '1' && c <= '9') {
            throw r.notYet("back reference \\" + c);
        }
        if ("pPAzZGkhHvVRXN".indexOf(c) >= 0) {
            throw r.notYet("escape \\" + c);
        }
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
            throw r.syntax("Illegal/unsupported escape sequence");
        }
        return c;
    }

    // JLS-style octal escape of java.util.regex: \0n, \0nn, \0mnn with m <= 3.
    private static char octal(Regex r) {
        int v = 0;
        int digits = 0;
        while (digits < 3 && r.more() && r.peek() >= '0' && r.peek() <= '7') {
            int next = v * 8 + (r.peek() - '0');
            if (next > 0377) {
                break;
            }
            v = next;
            r.next();
            digits++;
        }
        if (digits == 0) {
            throw r.syntax("Illegal octal escape sequence");
        }
        return (char) v;
    }

    private static int hex(Regex r, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            int d = r.more() ? Character.digit(r.peek(), 16) : -1;
            if (d < 0) {
                throw r.syntax("Illegal hexadecimal escape sequence");
            }
            r.next();
            v = v * 16 + d;
        }
        return v;
    }

    /** A bracket class, the parser just past `[`. */
    static CharTest bracket(Regex r) {
        boolean negate = r.more() && r.peek() == '^';
        if (negate) {
            r.next();
        }
        final List<char[]> ranges = new ArrayList<>();
        final List<CharTest> tests = new ArrayList<>();
        if (r.more() && r.peek() == ']') {
            throw r.notYet("']' first in a class");
        }
        while (true) {
            if (!r.more()) {
                throw r.syntax("Unclosed character class");
            }
            char c = r.peek();
            if (c == ']') {
                r.next();
                break;
            }
            if (c == '[') {
                throw r.notYet("nested class");
            }
            Object lo = item(r);
            if (lo instanceof CharTest) {
                tests.add((CharTest) lo);
                continue;
            }
            char from = (Character) lo;
            char to = from;
            if (r.more() && r.peek() == '-') {
                r.next();
                if (r.more() && r.peek() == ']') {
                    ranges.add(new char[] {'-', '-'});
                } else {
                    Object hi = item(r);
                    if (hi instanceof CharTest) {
                        throw r.syntax("Illegal character range");
                    }
                    to = (Character) hi;
                    if (to < from) {
                        throw r.syntax("Illegal character range");
                    }
                }
            }
            ranges.add(new char[] {from, to});
        }
        return set(ranges, tests, negate, r.ignoreCase());
    }

    private static Object item(Regex r) {
        char c = r.peek();
        if (c == '\\') {
            return escapeItem(r);
        }
        if (c == '&' && r.more()) {
            r.next();
            if (r.more() && r.peek() == '&') {
                throw r.notYet("class intersection");
            }
            return c;
        }
        return r.next();
    }

    private static CharTest set(final List<char[]> ranges, final List<CharTest> tests,
            final boolean negate, final boolean ignoreCase) {
        return new CharTest() {
            boolean test(char x) {
                boolean in = has(x) || (ignoreCase && (has(Character.toUpperCase(x)) || has(Character.toLowerCase(x))));
                return in != negate;
            }

            private boolean has(char x) {
                for (char[] g : ranges) {
                    if (x >= g[0] && x <= g[1]) {
                        return true;
                    }
                }
                for (CharTest t : tests) {
                    if (t.test(x)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }
}
