package java.util;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * printf-style formatting. Numbers always use en-US conventions ('.' decimal,
 * ',' grouping) whatever the locale; date/time conversions (%t) are not
 * supported yet (S11) and BigInteger/BigDecimal do not exist.
 */
public final class Formatter implements Closeable, Flushable {
    private Appendable a;
    private final Locale l;
    private IOException lastException;

    public Formatter() {
        this(new StringBuilder(), Locale.getDefault(Locale.Category.FORMAT));
    }

    public Formatter(Appendable a) {
        this(a, Locale.getDefault(Locale.Category.FORMAT));
    }

    public Formatter(Locale l) {
        this(new StringBuilder(), l);
    }

    public Formatter(Appendable a, Locale l) {
        this.a = a == null ? new StringBuilder() : a;
        this.l = l;
    }

    public Formatter(PrintStream ps) {
        this(Objects.requireNonNull(ps), Locale.getDefault(Locale.Category.FORMAT));
    }

    public Formatter(OutputStream os) {
        this(new PrintStream(os), Locale.getDefault(Locale.Category.FORMAT));
    }

    private void ensureOpen() {
        if (a == null) {
            throw new FormatterClosedException();
        }
    }

    public Locale locale() {
        ensureOpen();
        return l;
    }

    public Appendable out() {
        ensureOpen();
        return a;
    }

    public String toString() {
        ensureOpen();
        return a.toString();
    }

    public void flush() {
        ensureOpen();
        if (a instanceof Flushable) {
            try {
                ((Flushable) a).flush();
            } catch (IOException e) {
                lastException = e;
            }
        }
    }

    public void close() {
        if (a == null) {
            return;
        }
        try {
            if (a instanceof Closeable) {
                ((Closeable) a).close();
            }
        } catch (IOException e) {
            lastException = e;
        } finally {
            a = null;
        }
    }

    public IOException ioException() {
        return lastException;
    }

    public Formatter format(String format, Object... args) {
        return format(l, format, args);
    }

    /** Parses the whole format string first, so a malformed one writes nothing. */
    public Formatter format(Locale l, String format, Object... args) {
        ensureOpen();
        Object[] parts = parse(format);
        int last = -1;
        int lasto = -1;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] instanceof String) {
                emit((String) parts[i]);
                continue;
            }
            FormatSpec s = (FormatSpec) parts[i];
            if (s.conv == '%' && !s.dateTime) {
                emit("%");
            } else if (s.conv == 'n' && !s.dateTime) {
                emit(System.lineSeparator());
            } else {
                int at = s.index == -1 ? last : (s.index == 0 ? ++lasto : s.index - 1);
                if (s.index != -1) {
                    last = at;
                }
                if (at < 0 || (args != null && at > args.length - 1)) {
                    throw new MissingFormatArgumentException(s.toString());
                }
                print(s, args == null ? null : args[at]);
            }
        }
        return this;
    }

    /** The format string as literal Strings and FormatSpecs, in order. */
    private static Object[] parse(String format) {
        Object[] parts = new Object[8];
        int count = 0;
        int n = format.length();
        int i = 0;
        while (i < n) {
            int pct = format.indexOf('%', i);
            Object part;
            if (pct < 0) {
                part = format.substring(i);
                i = n;
            } else if (pct > i) {
                part = format.substring(i, pct);
                i = pct;
            } else {
                FormatSpec s = FormatSpec.parse(format, pct);
                part = s;
                i = s.end;
            }
            if (count == parts.length) {
                parts = Arrays.copyOf(parts, count * 2);
            }
            parts[count++] = part;
        }
        return Arrays.copyOf(parts, count);
    }

    private void emit(String s) {
        try {
            a.append(s);
        } catch (IOException e) {
            lastException = e;
        }
    }

    private void emitText(FormatSpec s, String text) {
        if (s.precision != -1 && s.precision < text.length()) {
            text = text.substring(0, s.precision);
        }
        emit(s.justify(s.upper ? text.toUpperCase() : text));
    }

    private void print(FormatSpec s, Object arg) {
        if (s.dateTime) {
            if (arg != null) {
                throw new IllegalFormatConversionException(s.lower(), arg.getClass());
            }
            emitText(s, "null");
            return;
        }
        switch (s.conv) {
            case 'b': case 'B':
                emitText(s, arg == null ? "false" : (arg instanceof Boolean ? arg.toString() : "true"));
                break;
            case 'h': case 'H':
                emitText(s, arg == null ? "null" : Integer.toHexString(arg.hashCode()));
                break;
            case 's': case 'S':
                printString(s, arg);
                break;
            case 'c': case 'C':
                printChar(s, arg);
                break;
            case 'd': case 'o': case 'x': case 'X':
                printNumber(s, arg, false);
                break;
            default:
                printNumber(s, arg, true);
                break;
        }
    }

    private void printNumber(FormatSpec s, Object arg, boolean floating) {
        if (arg == null) {
            emitText(s, "null");
        } else {
            emit(floating ? FormatNumbers.floating(s, arg) : FormatNumbers.integer(s, arg));
        }
    }

    private void printString(FormatSpec s, Object arg) {
        if (arg instanceof Formattable) {
            int flags = (s.has(FormatSpec.MINUS) ? FormattableFlags.LEFT_JUSTIFY : 0)
                    | (s.upper ? FormattableFlags.UPPERCASE : 0)
                    | (s.has(FormatSpec.ALT) ? FormattableFlags.ALTERNATE : 0);
            ((Formattable) arg).formatTo(this, flags, s.width, s.precision);
            return;
        }
        if (s.has(FormatSpec.ALT)) {
            throw s.mismatch(FormatSpec.ALT);
        }
        emitText(s, String.valueOf(arg));
    }

    private void printChar(FormatSpec s, Object arg) {
        if (arg == null) {
            emitText(s, "null");
        } else if (arg instanceof Character) {
            emitText(s, String.valueOf(((Character) arg).charValue()));
        } else if (arg instanceof Byte || arg instanceof Short || arg instanceof Integer) {
            int cp = ((Number) arg).intValue();
            if (!Character.isValidCodePoint(cp)) {
                throw new IllegalFormatCodePointException(cp);
            }
            emitText(s, new String(Character.toChars(cp)));
        } else {
            throw new IllegalFormatConversionException(s.lower(), arg.getClass());
        }
    }
}
