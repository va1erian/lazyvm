package java.io;

import java.util.Formatter;
import java.util.Locale;

public class PrintWriter extends Writer {
    protected Writer out;
    private final boolean autoFlush;
    private boolean trouble;
    /** The stream given to the constructor, when it is a PrintStream: checkError asks it. */
    private PrintStream printStream;
    private Formatter formatter;

    public PrintWriter(Writer out) {
        this(out, false);
    }

    public PrintWriter(Writer out, boolean autoFlush) {
        super(out);
        this.out = out;
        this.autoFlush = autoFlush;
    }

    public PrintWriter(OutputStream out) {
        this(out, false);
    }

    // Spec R5: over a stream, a BufferedWriter over an OutputStreamWriter.
    public PrintWriter(OutputStream out, boolean autoFlush) {
        this(new BufferedWriter(new OutputStreamWriter(out)), autoFlush);
        if (out instanceof PrintStream) {
            printStream = (PrintStream) out;
        }
    }

    public PrintWriter(String fileName) throws FileNotFoundException {
        this(new FileOutputStream(fileName), false);
    }

    /** The charset is looked up before the file is opened, so a bad name creates no file. */
    public PrintWriter(String fileName, String csn) throws FileNotFoundException, UnsupportedEncodingException {
        this(lazy.internal.Encodings.forName(csn), new FileOutputStream(fileName));
    }

    public PrintWriter(File file) throws FileNotFoundException {
        this(new FileOutputStream(file), false);
    }

    public PrintWriter(File file, String csn) throws FileNotFoundException, UnsupportedEncodingException {
        this(lazy.internal.Encodings.forName(csn), new FileOutputStream(file));
    }

    private PrintWriter(java.nio.charset.Charset cs, OutputStream out) {
        this(new BufferedWriter(new OutputStreamWriter(out, cs)), false);
    }

    private void ensureOpen() throws IOException {
        if (out == null) {
            throw new IOException("Stream closed");
        }
    }

    public void flush() {
        try {
            synchronized (lock) {
                ensureOpen();
                out.flush();
            }
        } catch (IOException x) {
            trouble = true;
        }
    }

    public void close() {
        try {
            synchronized (lock) {
                if (out != null) {
                    out.close();
                    out = null;
                }
            }
        } catch (IOException x) {
            trouble = true;
        }
    }

    public boolean checkError() {
        if (out != null) {
            flush();
        }
        // Observed on the oracle: a wrapped PrintWriter or PrintStream
        // reports its own state.
        if (out instanceof PrintWriter) {
            return ((PrintWriter) out).checkError();
        }
        if (printStream != null) {
            return printStream.checkError();
        }
        return trouble;
    }

    protected void setError() {
        trouble = true;
    }

    protected void clearError() {
        trouble = false;
    }

    public void write(int c) {
        try {
            synchronized (lock) {
                ensureOpen();
                out.write(c);
            }
        } catch (IOException x) {
            trouble = true;
        }
    }

    public void write(char[] buf, int off, int len) {
        try {
            synchronized (lock) {
                ensureOpen();
                out.write(buf, off, len);
            }
        } catch (IOException x) {
            trouble = true;
        }
    }

    public void write(char[] buf) {
        write(buf, 0, buf.length);
    }

    public void write(String s, int off, int len) {
        try {
            synchronized (lock) {
                ensureOpen();
                out.write(s, off, len);
            }
        } catch (IOException x) {
            trouble = true;
        }
    }

    public void write(String s) {
        write(s, 0, s.length());
    }

    private void newLine() {
        try {
            synchronized (lock) {
                ensureOpen();
                out.write(System.lineSeparator());
                if (autoFlush) {
                    out.flush();
                }
            }
        } catch (IOException x) {
            trouble = true;
        }
    }

    public void print(boolean b) {
        write(String.valueOf(b));
    }

    public void print(char c) {
        write(c);
    }

    public void print(int i) {
        write(String.valueOf(i));
    }

    public void print(long l) {
        write(String.valueOf(l));
    }

    public void print(float f) {
        write(String.valueOf(f));
    }

    public void print(double d) {
        write(String.valueOf(d));
    }

    public void print(char[] s) {
        write(s);
    }

    public void print(String s) {
        write(String.valueOf(s));
    }

    public void print(Object obj) {
        write(String.valueOf(obj));
    }

    public void println() {
        newLine();
    }

    public void println(boolean x) {
        synchronized (lock) {
            print(x);
            println();
        }
    }

    public void println(char x) {
        synchronized (lock) {
            print(x);
            println();
        }
    }

    public void println(int x) {
        synchronized (lock) {
            print(x);
            println();
        }
    }

    public void println(long x) {
        synchronized (lock) {
            print(x);
            println();
        }
    }

    public void println(float x) {
        synchronized (lock) {
            print(x);
            println();
        }
    }

    public void println(double x) {
        synchronized (lock) {
            print(x);
            println();
        }
    }

    public void println(char[] x) {
        synchronized (lock) {
            print(x);
            println();
        }
    }

    public void println(String x) {
        synchronized (lock) {
            print(x);
            println();
        }
    }

    public void println(Object x) {
        String s = String.valueOf(x);
        synchronized (lock) {
            print(s);
            println();
        }
    }

    public PrintWriter printf(String format, Object... args) {
        return format(format, args);
    }

    public PrintWriter printf(Locale l, String format, Object... args) {
        return format(l, format, args);
    }

    public PrintWriter format(String format, Object... args) {
        return formatIn(Locale.getDefault(Locale.Category.FORMAT), format, args);
    }

    public PrintWriter format(Locale l, String format, Object... args) {
        return formatIn(l, format, args);
    }

    /** Shared by both format overloads, so neither calls the other overridable one. */
    private PrintWriter formatIn(Locale l, String format, Object... args) {
        try {
            synchronized (lock) {
                ensureOpen();
                if (formatter == null || formatter.locale() != l) {
                    formatter = new Formatter(this, l);
                }
                formatter.format(l, format, args);
                if (autoFlush) {
                    out.flush();
                }
            }
        } catch (IOException x) {
            trouble = true;
        }
        return this;
    }

    public PrintWriter append(CharSequence csq) {
        write(String.valueOf(csq));
        return this;
    }

    public PrintWriter append(CharSequence csq, int start, int end) {
        CharSequence cs = csq == null ? "null" : csq;
        write(cs.subSequence(start, end).toString());
        return this;
    }

    public PrintWriter append(char c) {
        write(c);
        return this;
    }
}
