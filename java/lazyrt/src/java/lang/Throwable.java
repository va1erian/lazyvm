package java.lang;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Serializable;

public class Throwable implements Serializable {
    private static final StackTraceElement[] NO_TRACE = new StackTraceElement[0];
    // Marks a throwable built with writableStackTrace == false.
    private static final StackTraceElement[] FROZEN = new StackTraceElement[0];
    private static final Throwable[] NO_SUPPRESSED = new Throwable[0];

    private transient Object backtrace;
    private String detailMessage;
    // `this` means "not set yet"; null means "known to have no cause".
    private Throwable cause = this;
    private StackTraceElement[] stackTrace;
    // null when suppression is disabled.
    private Throwable[] suppressed = NO_SUPPRESSED;

    public Throwable() {
        fillInStackTrace();
    }

    public Throwable(String message) {
        fillInStackTrace();
        detailMessage = message;
    }

    public Throwable(String message, Throwable cause) {
        fillInStackTrace();
        detailMessage = message;
        this.cause = cause;
    }

    public Throwable(Throwable cause) {
        fillInStackTrace();
        detailMessage = (cause == null ? null : cause.toString());
        this.cause = cause;
    }

    protected Throwable(String message, Throwable cause, boolean enableSuppression,
            boolean writableStackTrace) {
        if (writableStackTrace) {
            fillInStackTrace();
        } else {
            stackTrace = FROZEN;
        }
        detailMessage = message;
        this.cause = cause;
        if (!enableSuppression) {
            suppressed = null;
        }
    }

    public String getMessage() {
        return detailMessage;
    }

    public String getLocalizedMessage() {
        return getMessage();
    }

    public synchronized Throwable getCause() {
        return cause == this ? null : cause;
    }

    public synchronized Throwable initCause(Throwable cause) {
        if (this.cause != this) {
            throw new IllegalStateException("Can't overwrite cause with " + String.valueOf(cause),
                    this);
        }
        if (cause == this) {
            throw new IllegalArgumentException("Self-causation not permitted", this);
        }
        this.cause = cause;
        return this;
    }

    public String toString() {
        String name = getClass().getName();
        String message = getLocalizedMessage();
        return message != null ? name + ": " + message : name;
    }

    public synchronized Throwable fillInStackTrace() {
        if (stackTrace != FROZEN) {
            fillInStackTrace0();
            stackTrace = null;
        }
        return this;
    }

    private native void fillInStackTrace0();

    private native StackTraceElement[] getStackTrace0();

    private StackTraceElement[] ourTrace() {
        StackTraceElement[] t = stackTrace;
        if (t == null) {
            t = getStackTrace0();
            if (t == null) {
                t = NO_TRACE;
            }
            stackTrace = t;
        }
        return t;
    }

    public StackTraceElement[] getStackTrace() {
        return ourTrace().clone();
    }

    public void setStackTrace(StackTraceElement[] stackTrace) {
        StackTraceElement[] copy = stackTrace.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) {
                throw new NullPointerException("stackTrace[" + i + "]");
            }
        }
        if (this.stackTrace != FROZEN) {
            this.stackTrace = copy;
        }
    }

    public final synchronized void addSuppressed(Throwable exception) {
        if (exception == this) {
            throw new IllegalArgumentException("Self-suppression not permitted", exception);
        }
        if (exception == null) {
            throw new NullPointerException("Cannot suppress a null exception.");
        }
        if (suppressed == null) {
            return;
        }
        Throwable[] bigger = new Throwable[suppressed.length + 1];
        System.arraycopy(suppressed, 0, bigger, 0, suppressed.length);
        bigger[suppressed.length] = exception;
        suppressed = bigger;
    }

    public final synchronized Throwable[] getSuppressed() {
        if (suppressed == null || suppressed.length == 0) {
            return NO_SUPPRESSED;
        }
        return suppressed.clone();
    }

    public void printStackTrace() {
        printStackTrace(System.err);
    }

    /**
     * Prints line by line as the trace is walked, so an autoflush stream
     * flushes each line and a failing toString leaves the earlier lines out.
     */
    public void printStackTrace(PrintStream s) {
        synchronized (s) {
            printTrace(new Sink(s, null), NO_TRACE, "", "", new Seen());
        }
    }

    public void printStackTrace(PrintWriter s) {
        synchronized (s) {
            printTrace(new Sink(null, s), NO_TRACE, "", "", new Seen());
        }
    }

    /** Prints this throwable, eliding the frames it shares with the enclosing trace. */
    private void printTrace(Sink s, StackTraceElement[] enclosing, String caption,
            String prefix, Seen seen) {
        if (seen.contains(this)) {
            s.line(prefix + caption + "[CIRCULAR REFERENCE: " + this + "]");
            return;
        }
        seen.add(this);
        StackTraceElement[] trace = ourTrace();
        int m = trace.length - 1;
        int n = enclosing.length - 1;
        while (m >= 0 && n >= 0 && trace[m].equals(enclosing[n])) {
            m--;
            n--;
        }
        int common = trace.length - 1 - m;
        s.line(prefix + caption + this);
        for (int i = 0; i <= m; i++) {
            s.line(prefix + "\tat " + trace[i]);
        }
        if (common != 0) {
            s.line(prefix + "\t... " + common + " more");
        }
        Throwable[] sup = getSuppressed();
        for (int i = 0; i < sup.length; i++) {
            sup[i].printTrace(s, trace, "Suppressed: ", prefix + "\t", seen);
        }
        Throwable c = getCause();
        if (c != null) {
            c.printTrace(s, trace, "Caused by: ", prefix, seen);
        }
    }

    /** Where a trace goes: println on a PrintStream or a PrintWriter. */
    private static final class Sink {
        private final PrintStream stream;
        private final PrintWriter writer;

        Sink(PrintStream stream, PrintWriter writer) {
            this.stream = stream;
            this.writer = writer;
        }

        void line(String text) {
            if (stream != null) {
                stream.println(text);
            } else {
                writer.println(text);
            }
        }
    }

    /** Identity set of the throwables already printed. */
    private static final class Seen {
        private Throwable[] items = new Throwable[4];
        private int count;

        boolean contains(Throwable t) {
            for (int i = 0; i < count; i++) {
                if (items[i] == t) {
                    return true;
                }
            }
            return false;
        }

        void add(Throwable t) {
            if (count == items.length) {
                Throwable[] bigger = new Throwable[count * 2];
                System.arraycopy(items, 0, bigger, 0, count);
                items = bigger;
            }
            items[count++] = t;
        }
    }
}
