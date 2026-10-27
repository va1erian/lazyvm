package util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;

/** How PrintWriter(OutputStream) and PrintStream hand bytes to the stream: blocks, failures, close. */
public class WriterBlocks {
    /** Logs each call; the first `failures` writes throw. */
    static class Log extends OutputStream {
        final StringBuilder log = new StringBuilder();
        int failures;

        Log(int failures) {
            this.failures = failures;
        }

        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        public void write(byte[] b, int off, int len) throws IOException {
            if (failures > 0) {
                failures--;
                log.append("X").append(len).append(' ');
                throw new IOException("fail");
            }
            log.append("w").append(len);
            if (len < 8) {
                log.append(':').append(new String(b, off, len, "UTF-8"));
            }
            log.append(' ');
        }

        public void flush() {
            log.append("F ");
        }

        public void close() {
            log.append("C ");
        }
    }

    static String blocks(int n, String tail) {
        Log o = new Log(0);
        PrintWriter pw = new PrintWriter(o);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append('a');
        }
        pw.write(sb.toString() + tail);
        pw.flush();
        return n + "+" + tail.length() + ": " + o.log;
    }

    public static void main(String[] args) {
        String[] tails = {"é", "€", new String(Character.toChars(0x1F600)), "éx", "b"};
        int[] ns = {8188, 8189, 8190, 8191, 16381, 16382};
        for (String t : tails) {
            for (int n : ns) {
                System.out.println(blocks(n, t));
            }
        }

        Log o = new Log(1);
        PrintWriter pw = new PrintWriter(o);
        pw.print("abc");
        pw.flush();
        pw.print("def");
        pw.close();
        System.out.println(o.log + "| " + pw.checkError());

        o = new Log(1);
        pw = new PrintWriter(o);
        pw.print("abc");
        pw.close();
        pw.close();
        System.out.println(o.log + "| " + pw.checkError());

        o = new Log(0);
        PrintStream ps = new PrintStream(o);
        ps.print("x");
        ps.close();
        ps.close();
        new Exception("t").printStackTrace(ps);
        ps.println("after");
        ps.write('y');
        System.out.println(o.log + "| " + ps.checkError());

        Quiet q = new Quiet(new Log(0));
        q.print("a");
        q.close();
        q.clear();
        q.flush();
        System.out.println("flush after close: " + q.checkError());

        pw = new PrintWriter(new PrintStream(new Log(1)));
        pw.print("d");
        System.out.println("PrintWriter over failing PrintStream: " + pw.checkError());

        ps = new PrintStream(new PrintStream(new Log(1)));
        ps.print("c");
        System.out.println("PrintStream over failing PrintStream: " + ps.checkError());

        System.out.println("clean after close: " + new Quiet(new Log(0)).closedCheck());
    }

    static class Quiet extends PrintStream {
        Quiet(OutputStream out) {
            super(out);
        }

        void clear() {
            clearError();
        }

        boolean closedCheck() {
            print("z");
            close();
            return checkError();
        }
    }
}
