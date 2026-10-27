package io;

import java.io.*;
import java.util.concurrent.Callable;

/** The calls InputStreamReader and OutputStreamWriter (and PrintWriter/PrintStream on top of them)
 *  make on the byte stream they wrap. */
public class ReaderCalls {
    static StringBuilder log = new StringBuilder();

    static void t(String label, Callable<Object> c) {
        log.setLength(0);
        String r;
        try {
            r = String.valueOf(c.call());
        } catch (Throwable e) {
            r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        System.out.println(label + ": " + r + (log.length() == 0 ? "" : " log=" + log));
    }

    /** n ASCII 'a' bytes, at most `chunk` per read; available() reports what is left. */
    static class Src extends InputStream {
        int left, chunk;
        Src(int n, int chunk) { left = n; this.chunk = chunk; }
        public int read() { log.append("r1;"); return left-- > 0 ? 'a' : -1; }
        public int read(byte[] b, int off, int len) {
            int k = Math.min(Math.min(len, chunk), left);
            log.append("r" + len + (k <= 0 ? "=-1;" : "=" + k + ";"));
            if (k <= 0) return len == 0 ? 0 : -1;
            for (int i = 0; i < k; i++) b[off + i] = 'a';
            left -= k;
            return k;
        }
        public int available() { log.append("av;"); return left; }
        public void close() { log.append("close;"); }
    }

    static class Sink extends OutputStream {
        public void write(int b) { log.append("w1;"); }
        public void write(byte[] b, int off, int len) { log.append("w" + len + ";"); }
        public void flush() { log.append("fl;"); }
        public void close() { log.append("close;"); }
    }

    /** Logs the bytes written as text, with the platform line separator shown as '$'; a single
     *  byte as its number, since a lone '\n' is the separator on Unix but not on Windows. */
    static class TextSink extends Sink {
        public void write(int b) { log.append("w1[" + b + "];"); }
        public void write(byte[] b, int off, int len) { log.append("w[" + text(b, off, len) + "];"); }
        static String text(byte[] b, int off, int len) {
            return new String(b, off, len, java.nio.charset.StandardCharsets.ISO_8859_1).replace(System.lineSeparator(), "$").replace("\n", "\\n");
        }
    }

    public static void main(String[] args) throws Exception {
        char[] c = new char[20000];
        t("isr read()", () -> new InputStreamReader(new Src(100000, 100000), "UTF-8").read());
        t("isr read() small chunks", () -> new InputStreamReader(new Src(100, 7), "UTF-8").read());
        t("isr read(c,0,10)", () -> new InputStreamReader(new Src(100, 7), "UTF-8").read(c, 0, 10));
        t("isr read(c,0,20000)", () -> new InputStreamReader(new Src(100000, 100000), "UTF-8").read(c, 0, 20000));
        t("isr read(c,0,1) latin1", () -> new InputStreamReader(new Src(100000, 100000), "ISO-8859-1").read(c, 0, 1));
        t("isr read(c,0,1) twice", () -> { Reader r = new InputStreamReader(new Src(100000, 100000), "UTF-8"); r.read(c, 0, 1); return r.read(c, 0, 1); });
        t("isr read(c,0,0)", () -> new InputStreamReader(new Src(100, 7), "UTF-8").read(c, 0, 0));
        t("isr eof", () -> { Reader r = new InputStreamReader(new Src(2, 7), "UTF-8"); return r.read(c, 0, 10) + " " + r.read(c, 0, 10) + " " + r.read(c, 0, 10); });
        t("isr ready fresh", () -> new InputStreamReader(new Src(100, 7), "UTF-8").ready());
        t("isr ready empty", () -> new InputStreamReader(new Src(0, 7), "UTF-8").ready());
        t("isr ready after read", () -> { Reader r = new InputStreamReader(new Src(10, 7), "UTF-8"); r.read(); return r.ready(); });
        t("isr markSupported", () -> new InputStreamReader(new Src(10, 7), "UTF-8").markSupported());
        t("isr mark", () -> { new InputStreamReader(new Src(10, 7), "UTF-8").mark(1); return "?"; });
        t("isr close twice", () -> { Reader r = new InputStreamReader(new Src(10, 7), "UTF-8"); r.close(); r.close(); return "ok"; });
        t("isr read after close", () -> { Reader r = new InputStreamReader(new Src(10, 7), "UTF-8"); r.close(); return r.read(); });
        t("isr ready after close", () -> { Reader r = new InputStreamReader(new Src(10, 7), "UTF-8"); r.close(); return r.ready(); });
        t("isr skip 3", () -> { Reader r = new InputStreamReader(new Src(10, 100), "UTF-8"); return r.skip(3) + " " + r.read(c, 0, 20); });
        t("isr bounds", () -> new InputStreamReader(new Src(10, 100), "UTF-8").read(c, 19999, 2));
        t("isr spare, more ready", () -> { Reader r = new InputStreamReader(new Src(4, 2), "UTF-8"); r.read(); return r.read(c, 0, 3); });
        t("isr spare, none ready", () -> { Reader r = new InputStreamReader(new Src(2, 2), "UTF-8"); r.read(); return r.read(c, 0, 3); });
        t("isr spare, len 2", () -> { Reader r = new InputStreamReader(new Src(6, 2), "UTF-8"); r.read(); return r.read(c, 0, 2) + " " + r.read(c, 0, 2); });
        t("isr full buffer asks nothing", () -> new InputStreamReader(new Src(10, 3), "UTF-8").read(c, 0, 3));
        t("isr error after chars", () -> new InputStreamReader(new ByteArrayInputStream(new byte[]{0x41, (byte) 0xC3, 0x42}),
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()).read(c, 0, 10));

        t("osw write char", () -> { Writer w = new OutputStreamWriter(new Sink(), "UTF-8"); w.write('a'); return ""; });
        t("osw write char flush", () -> { Writer w = new OutputStreamWriter(new Sink(), "UTF-8"); w.write('a'); w.flush(); return ""; });
        t("osw write 8192", () -> { Writer w = new OutputStreamWriter(new Sink(), "UTF-8"); w.write(c, 0, 8192); return ""; });
        t("osw write 8193", () -> { Writer w = new OutputStreamWriter(new Sink(), "UTF-8"); w.write(c, 0, 8193); return ""; });
        t("osw write 8191 then 2", () -> { Writer w = new OutputStreamWriter(new Sink(), "UTF-8"); w.write(c, 0, 8191); w.write(c, 0, 2); return ""; });
        t("osw write 20000", () -> { Writer w = new OutputStreamWriter(new Sink(), "UTF-8"); w.write(c, 0, 20000); return ""; });
        t("osw write 3000 euros", () -> { Writer w = new OutputStreamWriter(new Sink(), "UTF-8"); char[] e = new char[3000]; java.util.Arrays.fill(e, '€'); w.write(e); return ""; });
        t("osw utf16 empty flush", () -> { Writer w = new OutputStreamWriter(new Sink(), "UTF-16"); w.flush(); w.write('a'); w.flush(); return ""; });
        t("osw close", () -> { Writer w = new OutputStreamWriter(new Sink(), "UTF-8"); w.write('a'); w.close(); w.close(); return ""; });
        t("osw flush after close", () -> { Writer w = new OutputStreamWriter(new Sink(), "UTF-8"); w.close(); w.flush(); return ""; });
        t("osw write empty", () -> { Writer w = new OutputStreamWriter(new Sink(), "UTF-8"); w.write(""); w.write(c, 0, 0); w.flush(); return ""; });
        t("osw bounds", () -> { new OutputStreamWriter(new Sink(), "UTF-8").write(c, 19999, 2); return ""; });

        t("PrintWriter(OutputStream) println", () -> { PrintWriter p = new PrintWriter(new TextSink()); p.println("ab"); return p.checkError(); });
        t("PrintWriter autoflush println", () -> { PrintWriter p = new PrintWriter(new TextSink(), true); p.print("ab"); p.println(); return ""; });
        t("PrintWriter autoflush print", () -> { PrintWriter p = new PrintWriter(new TextSink(), true); p.print("ab\n"); return ""; });
        t("PrintWriter close", () -> { PrintWriter p = new PrintWriter(new TextSink()); p.print('x'); p.close(); p.close(); p.print('y'); return p.checkError(); });
        t("PrintStream println", () -> { PrintStream p = new PrintStream(new TextSink()); p.println("ab"); return ""; });
        t("PrintStream autoflush", () -> { PrintStream p = new PrintStream(new TextSink(), true); p.print("a" + System.lineSeparator() + "b"); return ""; });
        t("PrintStream write byte autoflush", () -> { PrintStream p = new PrintStream(new TextSink(), true); p.write('a'); p.write('\n'); return ""; });
        t("PrintStream write(b) autoflush", () -> { PrintStream p = new PrintStream(new TextSink(), true); p.write("abc".getBytes(), 0, 3); return ""; });
        t("PrintStream close", () -> { PrintStream p = new PrintStream(new TextSink()); p.print('x'); p.close(); p.close(); p.print('y'); return p.checkError(); });
        t("PrintStream encoding", () -> { ByteArrayOutputStream bo = new ByteArrayOutputStream(); PrintStream p = new PrintStream(bo, false, "UTF-16BE"); p.print("€"); p.flush(); return bo.size(); });
        System.out.println("done");
    }
}
