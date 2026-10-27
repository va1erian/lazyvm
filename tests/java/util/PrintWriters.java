package util;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Locale;

/** StringWriter, PrintWriter, StringReader and Throwable.printStackTrace(PrintWriter). */
public class PrintWriters {
    static class Boom extends Writer {
        public void write(char[] c, int off, int len) throws IOException {
            throw new IOException("boom");
        }

        public void flush() throws IOException {
            throw new IOException("boom flush");
        }

        public void close() {
        }
    }

    public static void main(String[] args) throws IOException {
        StringWriter sw = new StringWriter();
        sw.write("abc");
        sw.write('d');
        sw.write(new char[] {'e', 'f', 'g'}, 1, 2);
        sw.write("hijkl", 1, 3);
        sw.append("m").append('n').append("opqr", 1, 3).append(null);
        sw.flush();
        sw.close();
        System.out.println(sw + " " + sw.getBuffer().length());

        StringWriter out = new StringWriter();
        PrintWriter pw = new PrintWriter(out);
        pw.print(1);
        pw.print('c');
        pw.print(2L);
        pw.print(1.5f);
        pw.print(2.5);
        pw.print(true);
        pw.print("s");
        pw.print((String) null);
        pw.print((Object) null);
        pw.print(new char[] {'x', 'y'});
        pw.println();
        pw.println(42);
        pw.println("line");
        pw.println(new char[] {'a', 'b'});
        pw.println(new StringBuilder("sb"));
        pw.printf("%5.1f|%-4s|%04d%n", 3.14159, "ab", 42);
        pw.printf(Locale.US, "%,d%n", 1234567);
        pw.format("%s=%d%n", "k", 7).format("%x", 255);
        pw.append("!").append('?').append("abc", 1, 2);
        System.out.println(pw.checkError());
        System.out.println(out.toString().replace(System.lineSeparator(), "|"));

        PrintWriter closed = new PrintWriter(new StringWriter());
        closed.close();
        closed.print("x");
        System.out.println("closed error: " + closed.checkError());
        closed.close();

        PrintWriter bad = new PrintWriter(new Boom());
        bad.print("x");
        System.out.println("boom error: " + bad.checkError());
        PrintWriter fine = new PrintWriter(new StringWriter(), true);
        fine.println("auto");
        System.out.println("fine error: " + fine.checkError());

        PrintStream sys = System.out;
        PrintWriter viaStream = new PrintWriter(sys, true);
        viaStream.println("to stdout through a stream");
        viaStream.printf("%s%n", "printf too");
        PrintWriter buffered = new PrintWriter(sys);
        buffered.print("flushed explicitly");
        buffered.println();
        buffered.flush();

        StringReader r = new StringReader("hello world");
        char[] buf = new char[5];
        System.out.println(r.read(buf) + new String(buf));
        System.out.println(r.skip(1) + " " + (char) r.read() + " " + r.ready() + " " + r.markSupported());
        r.mark(0);
        System.out.println(r.read(buf, 0, 5) + " " + r.read() + " " + r.read(buf, 0, 1));
        r.reset();
        System.out.println(r.read() + " " + r.skip(100) + " " + r.skip(100));
        r.close();
        try {
            r.read();
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
        Reader empty = new StringReader("");
        System.out.println(empty.read() + " " + empty.read(buf, 0, 0));

        RuntimeException e = new RuntimeException("outer", new IllegalStateException("inner"));
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        String text = trace.toString();
        StringBuilder heads = new StringBuilder();
        int from = 0;
        while (from < text.length()) {
            int nl = text.indexOf('\n', from);
            String line = text.substring(from, nl < 0 ? text.length() : nl).trim();
            if (text.charAt(from) != '\t' && line.length() > 0) {
                heads.append(line).append(" / ");
            }
            from = nl < 0 ? text.length() : nl + 1;
        }
        System.out.println(heads);
        System.out.println(trace.toString().contains("at util.PrintWriters.main"));
        System.out.println("done");
    }
}
