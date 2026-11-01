package java.time;

import java.time.format.DateTimeParseException;

/**
 * The scanner behind LocalDateTime.parse: `year-MM-ddTHH:mm[:ss[.fraction]]`, ASCII digits only.
 * Every failure is a DateTimeParseException naming the first position that does not fit.
 */
final class IsoParser {
    /** The text shown in messages is cut to this many characters. */
    private static final int SHOWN = 64;
    private static final int YEAR_DIGITS = 10;

    private final String text;
    private int pos;

    private IsoParser(String text) {
        this.text = text;
    }

    static LocalDateTime parse(String text) {
        return new IsoParser(text).run();
    }

    private LocalDateTime run() {
        long year = year();
        expect('-', '-');
        int month = two();
        expect('-', '-');
        int day = two();
        expect('T', 't');
        int hour = two();
        expect(':', ':');
        int minute = two();
        int second = 0;
        int nano = 0;
        if (pos < text.length() && text.charAt(pos) == ':' && digit(pos + 1) && digit(pos + 2)) {
            pos++;
            second = two();
            if (pos < text.length() && text.charAt(pos) == '.') {
                pos++;
                nano = fraction();
            }
        }
        if (pos != text.length()) {
            throw new DateTimeParseException(prefix() + " could not be parsed, unparsed text found at index " + pos,
                    text, pos);
        }
        try {
            return LocalDateTime.parsed(year, month, day, hour, minute, second, nano);
        } catch (DateTimeException e) {
            throw new DateTimeParseException(prefix() + " could not be parsed: " + e.getMessage(), text, 0, e);
        }
    }

    /**
     * Four digits, or a sign and more: `+` needs five digits or more, `-` four or more but not
     * zero. A bad shape is reported at the start of the digits, or at 0 when the sign or the
     * extra digits are the problem.
     */
    private long year() {
        char first = pos < text.length() ? text.charAt(pos) : 0;
        boolean plus = first == '+';
        boolean signed = plus || first == '-';
        if (signed) {
            pos++;
        }
        int start = pos;
        long value = 0;
        while (pos - start < YEAR_DIGITS && digit(pos)) {
            value = value * 10 + (text.charAt(pos++) - '0');
        }
        int digits = pos - start;
        if (digits < 4) {
            throw fail(start);
        }
        if (!signed ? digits > 4 : plus ? digits == 4 : value == 0) {
            throw fail(0);
        }
        return first == '-' ? -value : value;
    }

    private int fraction() {
        int value = 0;
        int n = 0;
        while (n < 9 && digit(pos)) {
            value = value * 10 + (text.charAt(pos++) - '0');
            n++;
        }
        for (; n < 9; n++) {
            value *= 10;
        }
        return value;
    }

    private int two() {
        if (!digit(pos) || !digit(pos + 1)) {
            throw fail(pos);
        }
        int value = (text.charAt(pos) - '0') * 10 + text.charAt(pos + 1) - '0';
        pos += 2;
        return value;
    }

    private void expect(char a, char b) {
        char c = pos < text.length() ? text.charAt(pos) : 0;
        if (c != a && c != b) {
            throw fail(pos);
        }
        pos++;
    }

    private boolean digit(int i) {
        return i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9';
    }

    private DateTimeParseException fail(int index) {
        return new DateTimeParseException(prefix() + " could not be parsed at index " + index, text, index);
    }

    private String prefix() {
        String shown = text.length() > SHOWN ? text.substring(0, SHOWN) + "..." : text;
        return "Text '" + shown + "'";
    }
}
