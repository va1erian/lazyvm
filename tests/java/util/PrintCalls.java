package util;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Locale;

/** The overridable PrintStream/PrintWriter calls a subclass sees, and what they carry. */
public class PrintCalls {
    /** Tees every byte array the stream writes, as a filtering subclass would. */
    static class Tee extends PrintStream {
        final StringBuilder seen = new StringBuilder();

        Tee(OutputStream out) {
            super(out);
        }

        public void write(byte[] b, int off, int len) {
            seen.append('[').append(new String(b, off, len)).append(']');
            super.write(b, off, len);
        }
    }

    static class Objects extends PrintStream {
        final StringBuilder seen = new StringBuilder();

        Objects() {
            super(new ByteArrayOutputStream());
        }

        public void println(Object o) {
            seen.append(o instanceof Throwable ? "T " : "S ");
            super.println(o);
        }
    }

    static class Formats extends PrintWriter {
        final StringBuilder seen = new StringBuilder();

        Formats() {
            super(new ByteArrayOutputStream());
        }

        public PrintWriter format(Locale l, String f, Object... a) {
            seen.append("formatLocale ");
            return super.format(l, f, a);
        }
    }

    public static void main(String[] args) {
        Tee tee = new Tee(new ByteArrayOutputStream());
        tee.print("a");
        tee.println(1);
        tee.append("xyz", 1, 2);
        tee.printf("%d!", 5);
        System.out.println("tee: " + tee.seen.toString().replace(System.lineSeparator(), "NL"));

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(bo);
        try {
            ps.printf("%s-%s", "1");
        } catch (RuntimeException e) {
            System.out.println("printf partial: [" + bo + "] " + e.getClass().getName());
        }

        Objects objs = new Objects();
        new Exception("top", new RuntimeException("cause")).printStackTrace(objs);
        String kinds = objs.seen.toString();
        System.out.println("trace println(Object): first " + kinds.substring(0, 2)
                + "rest strings " + !kinds.substring(2).contains("T"));

        Formats fw = new Formats();
        fw.format("%d", 1);
        fw.printf("%d", 2);
        fw.format(Locale.US, "%d", 3);
        System.out.println("PrintWriter format: " + fw.seen);
        System.out.println("done");
    }
}
