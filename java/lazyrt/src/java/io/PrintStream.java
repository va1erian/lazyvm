package java.io;

import java.util.Formatter;
import java.util.Locale;

/** A stream that formats values as UTF-8 text and never throws IOException. */
public class PrintStream extends FilterOutputStream implements Appendable, Closeable {
    private final boolean autoFlush;
    private boolean trouble;
    /** After close, writes only record trouble, as observed on the oracle. */
    private boolean closed;
    /** A high surrogate printed last, held for its low half (observed on the oracle). */
    private char held;

    public PrintStream(OutputStream out) {
        this(out, false);
    }

    public PrintStream(OutputStream out, boolean autoFlush) {
        super(out);
        if (out == null) {
            throw new NullPointerException("Null output stream");
        }
        this.autoFlush = autoFlush;
    }

    public PrintStream(OutputStream out, boolean autoFlush, String encoding)
            throws UnsupportedEncodingException {
        this(out, autoFlush);
        if (encoding == null) {
            throw new NullPointerException("Null charset name");
        }
        if (!encoding.equalsIgnoreCase("UTF-8") && !encoding.equalsIgnoreCase("UTF8")) {
            throw new UnsupportedEncodingException(encoding);
        }
    }

    public void flush() {
        if (refused()) {
            return;
        }
        try {
            out.flush();
        } catch (IOException e) {
            trouble = true;
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (held != 0) {
                held = 0;
                out.write('?');
            }
            out.close();
        } catch (IOException e) {
            trouble = true;
        }
    }

    /**
     * Observed on the oracle: once closed, a stream reports its own trouble;
     * while open, a PrintStream over a PrintStream reports the inner one's.
     */
    public boolean checkError() {
        if (closed) {
            return trouble;
        }
        flush();
        if (out instanceof PrintStream) {
            return ((PrintStream) out).checkError();
        }
        return trouble;
    }

    protected void setError() {
        trouble = true;
    }

    protected void clearError() {
        trouble = false;
    }

    /** True, with trouble recorded, when the stream is closed. */
    private boolean refused() {
        if (closed) {
            trouble = true;
        }
        return closed;
    }

    public void write(int b) {
        if (refused()) {
            return;
        }
        try {
            out.write(b);
            if (b == '\n' && autoFlush) {
                out.flush();
            }
        } catch (IOException e) {
            trouble = true;
        }
    }

    public void write(byte[] buf, int off, int len) {
        if (refused()) {
            return;
        }
        try {
            out.write(buf, off, len);
            if (autoFlush) {
                out.flush();
            }
        } catch (IOException e) {
            trouble = true;
        }
    }

    /** Encodes `s` once and writes it with a single call to the underlying stream. */
    private void writeText(String s, boolean newline) {
        if (refused()) {
            return;
        }
        if (held != 0) {
            s = held + s;
            held = 0;
        }
        if (!newline && !s.isEmpty() && Character.isHighSurrogate(s.charAt(s.length() - 1))) {
            held = s.charAt(s.length() - 1);
            s = s.substring(0, s.length() - 1);
            if (s.isEmpty()) {
                return;
            }
        }
        if (newline) {
            s = s + System.lineSeparator();
        }
        byte[] bytes = s.getBytes();
        try {
            out.write(bytes, 0, bytes.length);
            if (autoFlush && (newline || s.indexOf('\n') >= 0)) {
                out.flush();
            }
        } catch (IOException e) {
            trouble = true;
        }
    }

    public void print(boolean b) {
        writeText(String.valueOf(b), false);
    }

    public void print(char c) {
        writeText(String.valueOf(c), false);
    }

    public void print(int i) {
        writeText(String.valueOf(i), false);
    }

    public void print(long l) {
        writeText(String.valueOf(l), false);
    }

    public void print(float f) {
        writeText(String.valueOf(f), false);
    }

    public void print(double d) {
        writeText(String.valueOf(d), false);
    }

    public void print(char[] s) {
        writeText(new String(s), false);
    }

    public void print(String s) {
        writeText(String.valueOf(s), false);
    }

    public void print(Object obj) {
        writeText(String.valueOf(obj), false);
    }

    public void println() {
        writeText("", true);
    }

    public void println(boolean x) {
        writeText(String.valueOf(x), true);
    }

    public void println(char x) {
        writeText(String.valueOf(x), true);
    }

    public void println(int x) {
        writeText(String.valueOf(x), true);
    }

    public void println(long x) {
        writeText(String.valueOf(x), true);
    }

    public void println(float x) {
        writeText(String.valueOf(x), true);
    }

    public void println(double x) {
        writeText(String.valueOf(x), true);
    }

    public void println(char[] x) {
        writeText(new String(x), true);
    }

    public void println(String x) {
        writeText(String.valueOf(x), true);
    }

    public void println(Object x) {
        writeText(String.valueOf(x), true);
    }

    public PrintStream append(CharSequence csq) {
        print(String.valueOf(csq));
        return this;
    }

    public PrintStream append(CharSequence csq, int start, int end) {
        CharSequence cs = (csq == null ? "null" : csq);
        print(cs.subSequence(start, end).toString());
        return this;
    }

    public PrintStream append(char c) {
        print(c);
        return this;
    }

    public PrintStream printf(String format, Object... args) {
        return format(format, args);
    }

    public PrintStream printf(Locale l, String format, Object... args) {
        return format(l, format, args);
    }

    /** Formats into a buffer first, so the text reaches the stream in one write. */
    public PrintStream format(String format, Object... args) {
        return formatted(new Formatter().format(format, args).toString());
    }

    public PrintStream format(Locale l, String format, Object... args) {
        return formatted(new Formatter(l).format(format, args).toString());
    }

    /** With autoflush, formatted text is flushed even without a newline. */
    private PrintStream formatted(String text) {
        writeText(text, false);
        if (autoFlush) {
            flush();
        }
        return this;
    }
}
