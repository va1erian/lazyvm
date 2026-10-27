package java.io;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.util.Formatter;
import java.util.Locale;
import lazy.internal.Encodings;

/** A stream that formats values as text in its charset and never throws IOException. */
public class PrintStream extends FilterOutputStream implements Appendable, Closeable {
    private final boolean autoFlush;
    private boolean trouble;
    /** After close, writes only record trouble, as observed on the oracle. */
    private boolean closed;
    /** A high surrogate printed last, held for its low half (observed on the oracle). */
    private char held;
    /** The charset, or null for the default one, looked up at the first text. */
    private final Charset charset;
    /** One encoder for the stream's life, so UTF-16 writes its byte order mark once. */
    private CharsetEncoder encoder;

    public PrintStream(OutputStream out) {
        this(out, false);
    }

    public PrintStream(OutputStream out, boolean autoFlush) {
        this(out, autoFlush, (Charset) null);
    }

    public PrintStream(OutputStream out, boolean autoFlush, String encoding)
            throws UnsupportedEncodingException {
        this(out, autoFlush, charset(encoding));
    }

    public PrintStream(String fileName) throws FileNotFoundException {
        this(new FileOutputStream(fileName), false);
    }

    /** The charset is looked up before the file is opened, so a bad name creates no file. */
    public PrintStream(String fileName, String csn) throws FileNotFoundException, UnsupportedEncodingException {
        this(charset(csn), new FileOutputStream(fileName));
    }

    public PrintStream(File file) throws FileNotFoundException {
        this(new FileOutputStream(file), false);
    }

    public PrintStream(File file, String csn) throws FileNotFoundException, UnsupportedEncodingException {
        this(charset(csn), new FileOutputStream(file));
    }

    private PrintStream(Charset cs, OutputStream out) {
        this(out, false, cs);
    }

    private PrintStream(OutputStream out, boolean autoFlush, Charset cs) {
        super(out);
        if (out == null) {
            throw new NullPointerException("Null output stream");
        }
        this.autoFlush = autoFlush;
        this.charset = cs;
    }

    private static Charset charset(String name) throws UnsupportedEncodingException {
        if (name == null) {
            throw new NullPointerException("Null charset name");
        }
        return Encodings.forName(name);
    }

    /** `in` in the stream's charset; `end` at close, when a held surrogate is replaced. */
    private byte[] encode(CharBuffer in, boolean end) {
        if (encoder == null) {
            // Not in the constructor: System.out exists before the charsets can be set up.
            Charset cs = charset != null ? charset : Charset.defaultCharset();
            encoder = cs.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
        }
        ByteBuffer bb = ByteBuffer.allocate((int) (in.remaining() * encoder.maxBytesPerChar()) + 8);
        while (encoder.encode(in, bb, end).isOverflow()) {
            ByteBuffer bigger = ByteBuffer.allocate(bb.capacity() * 2);
            bb.flip();
            bb = bigger.put(bb);
        }
        byte[] bytes = new byte[bb.position()];
        bb.flip();
        bb.get(bytes);
        return bytes;
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
        try {
            if (held != 0) {
                // Through the overridable write, as observed; a lone half is the replacement.
                byte[] bytes = encode(CharBuffer.wrap(new char[] {held}), true);
                held = 0;
                write(bytes, 0, bytes.length);
            }
        } finally {
            // A subclass's write may throw: the stream is closed regardless,
            // and the wrapped stream is not closed then (observed).
            closed = true;
        }
        try {
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

    /**
     * Encodes `s` and hands it to this stream's own write(byte[],int,int),
     * so a subclass overriding it sees all text, as observed on the oracle.
     */
    private void writeText(String s) {
        if (refused()) {
            return;
        }
        String text = held != 0 ? held + s : s;
        held = 0;
        // The encoder keeps a trailing high surrogate back; observed: a byte order mark it
        // writes with that char still goes out at once (UTF-16).
        CharBuffer in = CharBuffer.wrap(text);
        byte[] bytes = encode(in, false);
        if (in.hasRemaining()) {
            held = in.get();
        }
        if (bytes.length == 0 && !text.isEmpty()) {
            return;
        }
        write(bytes, 0, bytes.length);
        // Spec R5: with auto-flush, text with a newline flushes again after the write's own flush.
        if (autoFlush && s.indexOf('\n') >= 0) {
            flush();
        }
    }

    public void print(boolean b) {
        writeText(String.valueOf(b));
    }

    public void print(char c) {
        writeText(String.valueOf(c));
    }

    public void print(int i) {
        writeText(String.valueOf(i));
    }

    public void print(long l) {
        writeText(String.valueOf(l));
    }

    public void print(float f) {
        writeText(String.valueOf(f));
    }

    public void print(double d) {
        writeText(String.valueOf(d));
    }

    public void print(char[] s) {
        writeText(new String(s));
    }

    public void print(String s) {
        writeText(String.valueOf(s));
    }

    public void print(Object obj) {
        writeText(String.valueOf(obj));
    }

    /** The line separator, written as its own print so subclasses see it as Java 8 shows. */
    private void newLine() {
        writeText(System.lineSeparator());
    }

    public void println() {
        newLine();
    }

    public void println(boolean x) {
        synchronized (this) {
            print(x);
            newLine();
        }
    }

    public void println(char x) {
        synchronized (this) {
            print(x);
            newLine();
        }
    }

    public void println(int x) {
        synchronized (this) {
            print(x);
            newLine();
        }
    }

    public void println(long x) {
        synchronized (this) {
            print(x);
            newLine();
        }
    }

    public void println(float x) {
        synchronized (this) {
            print(x);
            newLine();
        }
    }

    public void println(double x) {
        synchronized (this) {
            print(x);
            newLine();
        }
    }

    public void println(char[] x) {
        synchronized (this) {
            print(x);
            newLine();
        }
    }

    public void println(String x) {
        synchronized (this) {
            print(x);
            newLine();
        }
    }

    /** Observed on the oracle: this one goes through print(String). */
    public void println(Object x) {
        String s = String.valueOf(x);
        synchronized (this) {
            print(s);
            newLine();
        }
    }

    public PrintStream append(CharSequence csq) {
        print(String.valueOf(csq));
        return this;
    }

    public PrintStream append(CharSequence csq, int start, int end) {
        CharSequence cs = (csq == null ? "null" : csq);
        writeText(cs.subSequence(start, end).toString());
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

    /**
     * Formats straight into this stream through append, so text before a
     * bad specifier is already written when the exception is thrown. A closed
     * stream formats nothing and only records trouble (observed).
     */
    public PrintStream format(String format, Object... args) {
        if (!refused()) {
            new Formatter((Appendable) this).format(format, args);
        }
        return this;
    }

    public PrintStream format(Locale l, String format, Object... args) {
        if (!refused()) {
            new Formatter((Appendable) this, l).format(format, args);
        }
        return this;
    }
}
