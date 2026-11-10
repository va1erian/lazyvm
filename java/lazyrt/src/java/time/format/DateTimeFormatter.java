package java.time.format;

import java.time.DateTimeException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Objects;
import lazy.internal.TimeChecks;

/**
 * Prints a date-time from a pattern. The pattern is compiled once into a flat array of pairs
 * {letter, width}, or {0, index into the literals}; format is one loop over it. The letters are
 * y u M d H m s S a A (docs/design/time.md, section 5).
 */
public final class DateTimeFormatter {
    /** The letters that read one field, and the field each reads (A and a are handled apart). */
    private static final String FIELD_LETTERS = "yuMdHmsSa";
    private static final ChronoField[] FIELDS = {ChronoField.YEAR_OF_ERA, ChronoField.YEAR,
        ChronoField.MONTH_OF_YEAR, ChronoField.DAY_OF_MONTH, ChronoField.HOUR_OF_DAY,
        ChronoField.MINUTE_OF_HOUR, ChronoField.SECOND_OF_MINUTE, ChronoField.NANO_OF_SECOND,
        ChronoField.AMPM_OF_DAY};

    private final String pattern;
    private final int[] ops;
    private final String[] literals;

    private int opCount;
    private int literalCount;

    private DateTimeFormatter(String pattern) {
        this.pattern = pattern;
        int n = pattern.length();
        ops = new int[2 * n];
        literals = new String[n];
        StringBuilder lit = new StringBuilder();
        int i = 0;
        while (i < n) {
            char c = pattern.charAt(i);
            if (c == '\'') {
                i = quoted(pattern, i, lit);
            } else if (isLetter(c)) {
                int end = i;
                while (end < n && pattern.charAt(end) == c) {
                    end++;
                }
                checkLetter(c, end - i);
                addLiteral(lit);
                addOp(c, end - i);
                i = end;
            } else if (c == ']') {
                throw new IllegalArgumentException("Pattern invalid as it contains ] without previous [");
            } else if (c == '{' || c == '}' || c == '#' || c == '[') {
                // optional sections ([ ... ]) are left out, so `[` is refused like the other reserved ones
                throw new IllegalArgumentException("Pattern includes reserved character: '" + c + "'");
            } else {
                lit.append(c);
                i++;
            }
        }
        addLiteral(lit);
    }

    public static DateTimeFormatter ofPattern(String pattern) {
        return new DateTimeFormatter(Objects.requireNonNull(pattern, "pattern"));
    }

    private void addOp(int letter, int arg) {
        ops[2 * opCount] = letter;
        ops[2 * opCount + 1] = arg;
        opCount++;
    }

    /** Moves the pending literal text, if any, into the literals and adds the op that prints it. */
    private void addLiteral(StringBuilder lit) {
        if (lit.length() > 0) {
            literals[literalCount] = lit.toString();
            lit.setLength(0);
            addOp(0, literalCount++);
        }
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** Rejects a letter or a run length the formatter does not print. */
    private static void checkLetter(char c, int count) {
        if (FIELD_LETTERS.indexOf(c) < 0 && c != 'A') {
            throw new IllegalArgumentException("Unknown pattern letter: " + c);
        }
        if (c == 'y' || c == 'u') {
            if (count > 19) {
                throw new IllegalArgumentException("The minimum width must be from 1 to 19 inclusive but was " + count);
            }
        } else if (c == 'S') {
            if (count > 9) {
                throw new IllegalArgumentException("Minimum width must be from 0 to 9 inclusive but was " + count);
            }
        } else if (count > (c == 'a' || c == 'A' ? 1 : 2)) {
            throw new IllegalArgumentException("Too many pattern letters: " + c);
        }
    }

    /** Appends the quoted text starting at the quote `at` to `lit`; returns the index after it. */
    private static int quoted(String p, int at, StringBuilder lit) {
        int n = p.length();
        int end = at + 1;
        while (end < n) {
            if (p.charAt(end) == '\'') {
                if (end + 1 < n && p.charAt(end + 1) == '\'') {
                    end++;
                } else {
                    break;
                }
            }
            end++;
        }
        if (end >= n) {
            throw new IllegalArgumentException("Pattern ends with an incomplete string literal: " + p);
        }
        if (end == at + 1) {
            lit.append('\'');
        }
        for (int k = at + 1; k < end; k++) {
            char c = p.charAt(k);
            lit.append(c);
            if (c == '\'') {
                k++; // a doubled quote inside quotes is one quote
            }
        }
        return end + 1;
    }

    public String format(TemporalAccessor temporal) {
        Objects.requireNonNull(temporal, "temporal");
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < 2 * opCount; k += 2) {
            int letter = ops[k];
            if (letter == 0) {
                b.append(literals[ops[k + 1]]);
            } else {
                print(b, temporal, (char) letter, ops[k + 1]);
            }
        }
        return b.toString();
    }

    public String toString() {
        return pattern;
    }

    private static void print(StringBuilder b, TemporalAccessor t, char letter, int width) {
        if (letter == 'A') {
            b.append(((t.getLong(ChronoField.HOUR_OF_DAY) * 60 + t.getLong(ChronoField.MINUTE_OF_HOUR)) * 60
                    + t.getLong(ChronoField.SECOND_OF_MINUTE)) * 1000 + t.getLong(ChronoField.MILLI_OF_SECOND));
            return;
        }
        ChronoField field = FIELDS[FIELD_LETTERS.indexOf(letter)];
        long v = t.getLong(field);
        if (letter == 'y' || letter == 'u') {
            year(b, v, width);
        } else if (letter == 'S') {
            fraction(b, v, width);
        } else if (letter == 'a') {
            b.append(v == 0 ? "AM" : v == 1 ? "PM" : Long.toString(v));
        } else {
            number(b, field, v, width);
        }
    }

    /** One letter prints the number as it is; two print exactly two digits. */
    private static void number(StringBuilder b, ChronoField field, long v, int width) {
        if (width == 1) {
            b.append(v);
            return;
        }
        // The width is checked before the sign, as the reference behaviour does.
        if (Long.toString(v).length() - (v < 0 ? 1 : 0) > width) {
            throw new DateTimeException("Field " + field + " cannot be printed as the value " + v
                    + " exceeds the maximum print width of " + width);
        }
        if (v < 0) {
            throw new DateTimeException("Field " + field + " cannot be printed as the value " + v
                    + " cannot be negative according to the SignStyle");
        }
        TimeChecks.pad(b, v, width);
    }

    /**
     * Two letters print the last two digits of the absolute value. Otherwise: a `-` for negative
     * years, then at least `width` digits, with a `+` before a positive year that has more digits
     * than the width when the width is 4 or more.
     */
    private static void year(StringBuilder b, long v, int width) {
        if (width == 2) {
            TimeChecks.pad(b, Math.abs(v % 100), 2);
            return;
        }
        if (v >= 0 && width >= 4 && Long.toString(v).length() > width) {
            b.append('+');
        }
        TimeChecks.pad(b, v, width);
    }

    /** The first `width` digits of the nano of second, truncated. */
    private static void fraction(StringBuilder b, long nano, int width) {
        TimeChecks.check("NanoOfSecond", 0, 999999999, nano);
        TimeChecks.pad(b, nano, 9);
        b.setLength(b.length() - 9 + width);
    }
}
