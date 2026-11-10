package util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.ErrorManager;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;

/**
 * StreamHandler writes through one encoder per stream with a bounded buffer, and reports every failure to
 * its error manager (docs/design/logging.md, sections 8 and 9).
 */
public class LoggingEncoding {
    static final List<String> reports = new ArrayList<>();

    static void p(String label, Object value) {
        System.out.println(label + ": " + value);
    }

    static LogRecord rec(String msg) {
        return new LogRecord(Level.INFO, msg);
    }

    static ErrorManager recording() {
        return new ErrorManager() {
            public void error(String m, Exception e, int c) {
                reports.add(m + "/" + e + "/" + c);
            }
        };
    }

    /** Formats the message as is, with a head and tail chosen by the test. */
    static class Plain extends Formatter {
        String head = "";
        String tail = "";

        public String format(LogRecord r) {
            return r.getMessage();
        }

        public String getHead(Handler h) {
            return head;
        }

        public String getTail(Handler h) {
            return tail;
        }
    }

    static class Counting extends ByteArrayOutputStream {
        int flushes;
        int closes;

        public void flush() {
            flushes++;
        }

        public void close() {
            closes++;
        }
    }

    /** Fails every write once `left` bytes have gone through. */
    static class Failing extends OutputStream {
        int left;
        final StringBuilder got = new StringBuilder();
        int closes;

        Failing(int left) {
            this.left = left;
        }

        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        public void write(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                throw new IOException("w");
            }
            left -= len;
            got.append(new String(b, off, len));
        }

        public void close() {
            closes++;
        }
    }

    static class Handover extends StreamHandler {
        Handover(OutputStream out, Formatter f) {
            super(out, f);
        }

        void use(OutputStream out) {
            setOutputStream(out);
        }
    }

    public static void main(String[] args) throws Exception {
        encoders();
        failures();
        headAndTail();
        errorManagerThrows();
        runtimeFailures();
        construction();
        bounded();
    }

    static void encoders() throws Exception {
        ByteArrayOutputStream u16 = new ByteArrayOutputStream();
        StreamHandler h = new StreamHandler(u16, new Plain());
        h.setEncoding("UTF-16");
        h.publish(rec("1"));
        h.publish(rec("2"));
        h.close();
        p("one byte order mark", Arrays.toString(u16.toByteArray()));
        ByteArrayOutputStream split = new ByteArrayOutputStream();
        StreamHandler s = new StreamHandler(split, new Plain());
        s.setEncoding("UTF-8");
        s.publish(rec("\ud83d"));
        s.publish(rec("\ude00"));
        s.close();
        p("surrogate pair split between records", Arrays.toString(split.toByteArray()));
        Counting c = new Counting();
        StreamHandler e = new StreamHandler(c, new Plain());
        e.publish(rec("R;"));
        e.setEncoding("ISO-8859-1");
        p("setEncoding flushes the stream once", c.flushes + " bytes=" + c);
    }

    static void failures() {
        reports.clear();
        Failing old = new Failing(0);
        Failing next = new Failing(100);
        Plain f = new Plain();
        f.tail = ";T";
        Handover h = new Handover(old, f);
        h.setErrorManager(recording());
        h.publish(rec("old"));
        h.use(next);
        h.publish(rec("new"));
        h.close();
        p("no stale bytes after a failed close", reports + " new=" + next.got + " old closed=" + old.closes);
        reports.clear();
        Failing broken = new Failing(0);
        Handover r = new Handover(broken, new Plain());
        r.setErrorManager(recording());
        r.publish(rec("a;"));
        r.flush();
        broken.left = 100;
        r.publish(rec("b;"));
        r.flush();
        p("a failed flush drops its bytes", reports + " got=" + broken.got);
    }

    static void headAndTail() {
        reports.clear();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] heads = {0};
        StreamHandler h = new StreamHandler(out, new Plain() {
            public String getHead(Handler x) {
                if (heads[0]++ == 0) {
                    throw new RuntimeException("head");
                }
                return "H;";
            }

            public String getTail(Handler x) {
                return "T;";
            }
        });
        h.setErrorManager(recording());
        h.publish(rec("1;"));
        h.publish(rec("2;"));
        h.close();
        p("a failing head is tried again", reports + " bytes=" + out + " heads=" + heads[0]);
        reports.clear();
        out.reset();
        StreamHandler nulls = new StreamHandler(out, new Plain() {
            public String getHead(Handler x) {
                return null;
            }

            public String getTail(Handler x) {
                return null;
            }
        });
        nulls.setErrorManager(recording());
        nulls.publish(rec("1"));
        nulls.close();
        p("null head and tail", reports + " bytes=" + out);
        reports.clear();
        Counting c = new Counting();
        StreamHandler t = new StreamHandler(c, new Plain() {
            public String getTail(Handler x) {
                throw new RuntimeException("tail");
            }
        });
        t.setErrorManager(recording());
        t.publish(rec("1;"));
        t.close();
        p("a failing tail", reports + " bytes=" + c + " closes=" + c.closes + " flushes=" + c.flushes);
        reports.clear();
        StreamHandler nf = new StreamHandler(out, new Formatter() {
            public String format(LogRecord r) {
                return null;
            }
        });
        out.reset();
        nf.setErrorManager(recording());
        nf.publish(rec("x"));
        nf.flush();
        p("format returns null", reports + " bytes=" + out.size());
    }

    static void errorManagerThrows() {
        StreamHandler h = new StreamHandler(new ByteArrayOutputStream(), new Formatter() {
            public String format(LogRecord r) {
                throw new RuntimeException("fmt");
            }
        });
        h.setErrorManager(new ErrorManager() {
            public void error(String m, Exception e, int c) {
                throw new IllegalStateException("manager " + c);
            }
        });
        PrintStream real = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            h.publish(rec("x"));
        } finally {
            System.setErr(real);
        }
        p("a throwing error manager does not escape publish", captured.toString().split("\r?\n")[0]);
    }

    /** A stream whose operations throw unchecked exceptions, which are failures too. */
    static class Unchecked extends Counting {
        boolean failWrite;
        boolean failFlush;

        public void write(byte[] b, int off, int len) {
            if (failWrite) {
                throw new java.io.UncheckedIOException(new IOException("write"));
            }
            super.write(b, off, len);
        }

        public void flush() {
            if (failFlush) {
                throw new IllegalStateException("flush");
            }
            super.flush();
        }
    }

    static void runtimeFailures() {
        reports.clear();
        Unchecked u = new Unchecked();
        u.failWrite = true;
        StreamHandler h = new StreamHandler(u, new Plain());
        h.setErrorManager(recording());
        h.publish(rec("a"));
        h.flush();
        p("a stream write throwing unchecked", reports);
        reports.clear();
        u.failWrite = false;
        u.failFlush = true;
        h.publish(rec("b"));
        h.flush();
        p("a stream flush throwing unchecked", reports + " bytes=" + u);
        reports.clear();
        h.close();
        p("close flushes first; a failed flush leaves the stream open", reports + " closes=" + u.closes);
        reports.clear();
        Counting plain = new Counting();
        StreamHandler ok = new StreamHandler(plain, new Plain());
        ok.publish(rec("z"));
        ok.close();
        p("close flushes then closes", plain.flushes + " " + plain.closes + " bytes=" + plain);
    }

    static class Watching extends StreamHandler {
        // Static: the constructor of StreamHandler calls the overrides before this class's fields exist.
        static final List<String> seen = new ArrayList<>();

        Watching(OutputStream out) {
            super(out, new Plain());
        }

        public void setEncoding(String name) throws java.io.UnsupportedEncodingException {
            seen.add("setEncoding " + name);
            super.setEncoding(name);
        }

        protected void setOutputStream(OutputStream out) {
            seen.add("setOutputStream");
            super.setOutputStream(out);
        }
    }

    static void construction() {
        Watching.seen.clear();
        new Watching(new ByteArrayOutputStream());
        p("the constructor goes through the overridable setters", Watching.seen);
    }

    static void bounded() {
        ByteArrayOutputStream big = new ByteArrayOutputStream();
        StreamHandler h = new StreamHandler(big, new Plain());
        char[] chars = new char[1000];
        Arrays.fill(chars, 'x');
        String text = new String(chars);
        for (int i = 0; i < 100; i++) {
            h.publish(rec(text));
        }
        p("bytes reach the stream before any flush", big.size() > 0);
        h.flush();
        p("nothing is lost", big.size());
    }
}
