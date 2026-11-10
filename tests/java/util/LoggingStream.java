// VMARGS: -Duser.timezone=UTC
package util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.logging.ConsoleHandler;
import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

/** Handler, StreamHandler, ConsoleHandler and ErrorManager (docs/design/logging.md, sections 8 to 10). */
public class LoggingStream {
    interface Op {
        Object run() throws Exception;
    }

    static String t(Op op) {
        try {
            return String.valueOf(op.run());
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    static void p(String label, Object value) {
        System.out.println(label + ": " + value);
    }

    static LogRecord rec(Level level, String msg) {
        LogRecord r = new LogRecord(level, msg);
        r.setMillis(1700000000123L);
        return r;
    }

    /** A stack-free exception: its printed form has no frames, so goldens never see a call stack. */
    static class Quiet extends Exception {
        Quiet(String m) {
            super(m);
            setStackTrace(new StackTraceElement[0]);
        }
    }

    static class Brackets extends Formatter {
        public String format(LogRecord r) {
            return "<" + r.getMessage() + ">";
        }

        public String getHead(Handler h) {
            return "[HEAD]";
        }

        public String getTail(Handler h) {
            return "[TAIL]";
        }
    }

    /** Remembers whether it was closed; its bytes are readable at any time. */
    static class Sink extends ByteArrayOutputStream {
        boolean closed;

        public void close() throws IOException {
            closed = true;
            super.close();
        }

        String text() {
            return toString().replace("\r\n", "\n");
        }
    }

    static class Subclass extends StreamHandler {
        void use(OutputStream out) {
            setOutputStream(out);
        }
    }

    static class Plain extends Handler {
        public void publish(LogRecord r) {}

        public void flush() {}

        public void close() {}

        void err(String m, Exception e, int code) {
            reportError(m, e, code);
        }
    }

    public static void main(String[] args) throws Exception {
        handlerBase();
        encodings();
        streamLifecycle();
        setOutputStream();
        streamFilter();
        consoleHandler();
        errors();
    }

    static void handlerBase() {
        Plain h = new Plain();
        p("defaults", h.getLevel() + " " + h.getFormatter() + " " + h.getFilter() + " " + h.getEncoding() + " "
                + h.getErrorManager().getClass().getName());
        StreamHandler s = new StreamHandler();
        p("StreamHandler defaults", s.getLevel() + " " + s.getFormatter().getClass().getName() + " " + s.getFilter()
                + " " + s.getEncoding() + " " + s.getErrorManager().getClass().getName());
        StreamHandler s2 = new StreamHandler(new ByteArrayOutputStream(), new Brackets());
        p("StreamHandler(out, f) defaults", s2.getLevel() + " " + (s2.getFormatter() instanceof Brackets) + " "
                + s2.getFilter() + " " + s2.getEncoding());
        ConsoleHandler c = new ConsoleHandler();
        p("ConsoleHandler defaults", c.getLevel() + " " + c.getFormatter().getClass().getName() + " " + c.getFilter()
                + " " + c.getEncoding() + " " + c.getErrorManager().getClass().getName());
        p("ConsoleHandler is a StreamHandler", c instanceof StreamHandler);
        p("StreamHandler(out) null out", t(() -> new StreamHandler(null, new Brackets())));
        p("StreamHandler(out) null formatter", t(() -> new StreamHandler(new ByteArrayOutputStream(), null)));
        p("setFormatter null", t(() -> {
            h.setFormatter(null);
            return "ok";
        }));
        p("setLevel null", t(() -> {
            h.setLevel(null);
            return "ok";
        }));
        p("setErrorManager null", t(() -> {
            h.setErrorManager(null);
            return "ok";
        }));
        Brackets b = new Brackets();
        h.setFormatter(b);
        h.setLevel(Level.FINE);
        ErrorManager em = new ErrorManager();
        h.setErrorManager(em);
        p("setters", (h.getFormatter() == b) + " " + h.getLevel() + " " + (h.getErrorManager() == em));
        h.setFilter(r -> r.getMessage().equals("keep"));
        p("filter", h.getFilter() != null);
        p("isLoggable", h.isLoggable(rec(Level.INFO, "keep")) + " " + h.isLoggable(rec(Level.INFO, "drop")) + " "
                + h.isLoggable(rec(Level.FINER, "keep")));
        p("isLoggable null", t(() -> h.isLoggable(null)));
        h.setFilter(null);
        h.setLevel(Level.OFF);
        p("level OFF", h.isLoggable(rec(Level.SEVERE, "x")) + " " + h.isLoggable(rec(Level.OFF, "x")));
        h.setLevel(Level.ALL);
        p("level ALL", h.isLoggable(rec(Level.FINEST, "x")) + " " + h.isLoggable(rec(Level.OFF, "x")));
        h.setLevel(Level.parse("555"));
        p("level 555", h.isLoggable(rec(Level.parse("554"), "x")) + " " + h.isLoggable(rec(Level.parse("555"), "x")));
    }

    static void encodings() throws Exception {
        Plain h = new Plain();
        h.setEncoding("UTF-8");
        p("setEncoding", h.getEncoding());
        h.setEncoding(null);
        p("setEncoding null", h.getEncoding());
        h.setEncoding("ISO-8859-1");
        p("setEncoding latin1", h.getEncoding());
        try {
            h.setEncoding("bogus");
        } catch (UnsupportedEncodingException e) {
            p("setEncoding bogus", e.getMessage());
        }
        p("encoding kept after failure", h.getEncoding());
        // The bytes a StreamHandler writes.
        ByteArrayOutputStream o1 = new ByteArrayOutputStream();
        StreamHandler utf = new StreamHandler(o1, new Brackets());
        utf.setEncoding("UTF-8");
        utf.publish(rec(Level.INFO, "é€"));
        utf.flush();
        p("utf-8 bytes", Arrays.toString(o1.toByteArray()));
        ByteArrayOutputStream o2 = new ByteArrayOutputStream();
        StreamHandler latin = new StreamHandler(o2, new Brackets());
        latin.setEncoding("ISO-8859-1");
        latin.publish(rec(Level.INFO, "é€"));
        latin.flush();
        p("latin1 bytes (unmappable becomes ?)", Arrays.toString(o2.toByteArray()));
        latin.setEncoding("UTF-8");
        latin.publish(rec(Level.INFO, "é"));
        latin.flush();
        p("encoding change keeps earlier bytes, no second head", Arrays.toString(o2.toByteArray()));
        ByteArrayOutputStream o3 = new ByteArrayOutputStream();
        StreamHandler dflt = new StreamHandler(o3, new Brackets());
        dflt.publish(rec(Level.INFO, "é"));
        dflt.flush();
        p("default encoding is the platform's", Arrays.toString(o3.toByteArray()));
    }

    static void streamLifecycle() {
        Sink o = new Sink();
        StreamHandler h = new StreamHandler(o, new Brackets());
        p("isLoggable", h.isLoggable(rec(Level.INFO, "a")) + " " + h.isLoggable(rec(Level.FINE, "a")));
        h.publish(rec(Level.INFO, "a"));
        p("buffered after publish", "[" + o.text() + "]");
        h.publish(rec(Level.FINE, "below level"));
        h.publish(rec(Level.WARNING, "b"));
        h.flush();
        p("after flush", "[" + o.text() + "] closed=" + o.closed);
        h.close();
        p("after close", "[" + o.text() + "] closed=" + o.closed);
        h.publish(rec(Level.SEVERE, "after close"));
        h.flush();
        p("publish after close", "[" + o.text() + "] " + h.isLoggable(rec(Level.SEVERE, "x")));
        h.close();
        p("close twice", "[" + o.text() + "]");

        Sink o2 = new Sink();
        new StreamHandler(o2, new Brackets()).close();
        p("close without records", "[" + o2.text() + "] closed=" + o2.closed);
        Sink o3 = new Sink();
        StreamHandler h3 = new StreamHandler(o3, new Brackets());
        h3.flush();
        p("flush without records", "[" + o3.text() + "] closed=" + o3.closed);

        StreamHandler none = new StreamHandler();
        p("no stream", none.isLoggable(rec(Level.SEVERE, "x")));
        none.publish(rec(Level.SEVERE, "x"));
        none.flush();
        none.close();
        p("no stream survives", "ok");

        Sink o4 = new Sink();
        StreamHandler h4 = new StreamHandler(o4, new SimpleFormatter());
        h4.publish(rec(Level.INFO, "default format"));
        h4.close();
        p("SimpleFormatter has no head or tail", "[" + o4.text() + "]");
    }

    static void setOutputStream() {
        Subclass h = new Subclass();
        h.setFormatter(new Brackets());
        Sink a = new Sink();
        Sink b = new Sink();
        h.use(a);
        p("loggable with stream", h.isLoggable(rec(Level.SEVERE, "x")));
        h.publish(rec(Level.INFO, "p5"));
        h.use(b);
        p("old stream finished and closed", "[" + a.text() + "] closed=" + a.closed);
        p("new stream untouched", "[" + b.text() + "] closed=" + b.closed);
        h.publish(rec(Level.INFO, "p6"));
        h.flush();
        p("new stream gets head again", "[" + b.text() + "]");
        p("null stream", t(() -> {
            h.use(null);
            return "ok";
        }));
        h.close();
        p("closed", b.closed);
    }

    static void streamFilter() {
        Sink o = new Sink();
        StreamHandler h = new StreamHandler(o, new Brackets());
        h.setFilter(r -> r.getMessage().startsWith("k"));
        h.publish(rec(Level.INFO, "keep"));
        h.publish(rec(Level.INFO, "drop"));
        h.flush();
        p("filter", "[" + o.text() + "]");
        h.setLevel(Level.ALL);
        h.setFilter(null);
        h.publish(rec(Level.FINEST, "all"));
        h.flush();
        p("level ALL", "[" + o.text() + "]");
    }

    static void consoleHandler() {
        PrintStream real = System.err;
        Sink captured = new Sink();
        PrintStream replacement = new PrintStream(captured, true);
        System.setErr(replacement);
        ConsoleHandler ch;
        try {
            ch = new ConsoleHandler();
        } finally {
            System.setErr(real);
        }
        ch.setFormatter(new Brackets());
        ch.publish(rec(Level.INFO, "to captured"));
        p("writes at once to the System.err of its creation", "[" + captured.text() + "]");
        ch.publish(rec(Level.FINE, "below"));
        ch.publish(rec(Level.SEVERE, "second"));
        p("level INFO", "[" + captured.text() + "]");
        ch.close();
        p("close adds no tail, closes nothing", "[" + captured.text() + "] closed=" + captured.closed);
        ch.publish(rec(Level.INFO, "after close"));
        p("publish after close", "[" + captured.text() + "]");

        System.setErr(replacement);
        ConsoleHandler def = new ConsoleHandler();
        System.setErr(real);
        def.publish(rec(Level.INFO, "hello"));
        def.publish(rec(Level.WARNING, "warn"));
        System.out.print("default format on the console handler:\n" + captured.text().substring(captured.text().indexOf("Nov")));
        System.err.println("real System.err still works");
        ConsoleHandler real1 = new ConsoleHandler();
        real1.setFormatter(new Brackets());
        real1.publish(rec(Level.INFO, "to the real stderr"));
        real1.setLevel(Level.ALL);
        real1.publish(rec(Level.FINEST, "all levels"));
        real1.close();
        System.err.println();
        System.err.println("end of stderr");
    }

    static void errors() {
        Formatter failing = new Formatter() {
            public String format(LogRecord r) {
                throw new IllegalStateException("fmt");
            }
        };
        StreamHandler h = new StreamHandler(new ByteArrayOutputStream(), failing);
        h.setErrorManager(new ErrorManager() {
            public void error(String m, Exception e, int c) {
                System.out.println("reported " + m + " " + e + " " + c);
            }
        });
        h.publish(rec(Level.INFO, "x"));
        p("publish survives a failing formatter", "ok");

        StreamHandler w = new StreamHandler(new OutputStream() {
            public void write(int b) throws IOException {
                throw new IOException("disk");
            }
        }, new Brackets());
        w.setErrorManager(new ErrorManager() {
            public void error(String m, Exception e, int c) {
                System.out.println("reported " + m + " " + e + " " + c);
            }
        });
        w.publish(rec(Level.INFO, "x"));
        w.flush();
        w.close();

        Plain plain = new Plain();
        plain.setErrorManager(new ErrorManager() {
            public void error(String m, Exception e, int c) {
                System.out.println("reportError " + m + " " + e.getMessage() + " " + c);
            }
        });
        plain.err("rep", new Quiet("re"), ErrorManager.FLUSH_FAILURE);
        p("codes", ErrorManager.GENERIC_FAILURE + " " + ErrorManager.WRITE_FAILURE + " " + ErrorManager.FLUSH_FAILURE
                + " " + ErrorManager.CLOSE_FAILURE + " " + ErrorManager.OPEN_FAILURE + " " + ErrorManager.FORMAT_FAILURE);

        // The default ErrorManager prints the first report of each instance to System.err and stays silent after.
        ErrorManager e1 = new ErrorManager();
        e1.error("m1", new Quiet("e1"), ErrorManager.CLOSE_FAILURE);
        e1.error("m2", new Quiet("e2"), ErrorManager.CLOSE_FAILURE);
        ErrorManager e2 = new ErrorManager();
        e2.error(null, null, 7);
        e2.error("never printed", null, 7);
        ErrorManager e3 = new ErrorManager();
        e3.error("only a message", null, ErrorManager.GENERIC_FAILURE);
        ErrorManager e4 = new ErrorManager();
        e4.error(null, new Quiet("only an exception"), ErrorManager.WRITE_FAILURE);
        System.err.println("end of error reports");
    }
}
