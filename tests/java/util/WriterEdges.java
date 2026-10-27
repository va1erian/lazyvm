package util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Formatter;

/** Surrogate pairs split across flushes, Writer bounds, specifiers in error messages. */
public class WriterEdges {
    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(Integer.toHexString(x & 0xff)).append(' ');
        }
        return sb.toString().trim();
    }

    static class Log extends OutputStream {
        final StringBuilder log = new StringBuilder();

        public void write(int b) {
            log.append("w1 ");
        }

        public void write(byte[] b, int off, int len) {
            log.append("w").append(len).append(' ');
        }

        public void flush() {
            log.append("F ");
        }

        public void close() {
            log.append("C");
        }
    }

    public static void main(String[] args) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        PrintWriter pw = new PrintWriter(bo);
        pw.print("x\ud83d");
        pw.flush();
        pw.print("\ude00");
        pw.flush();
        System.out.println(hex(bo.toByteArray()));

        bo = new ByteArrayOutputStream();
        pw = new PrintWriter(bo);
        for (int i = 0; i < 8191; i++) {
            pw.print('a');
        }
        pw.print('\ud83d');
        pw.print('\ude00');
        pw.flush();
        byte[] all = bo.toByteArray();
        System.out.println(all.length + " " + hex(new byte[] {all[8191], all[8192], all[8193], all[8194]}));

        bo = new ByteArrayOutputStream();
        pw = new PrintWriter(bo);
        pw.print("y\ud83d");
        pw.close();
        System.out.println(hex(bo.toByteArray()));

        Log log = new Log();
        new PrintWriter(log).close();
        System.out.println(log.log);

        try {
            new StringWriter().write("abc", 1, -1);
        } catch (IndexOutOfBoundsException e) {
            System.out.println(e);
        }

        String[] specs = {"%1$-d", "%3$+(30d", "%3$-%", "%2$#c", "%1$-08.3f"};
        for (String s : specs) {
            try {
                new Formatter().format(s, 1);
                System.out.println(s + " ok");
            } catch (RuntimeException e) {
                System.out.println(s + " " + e);
            }
        }
    }
}
