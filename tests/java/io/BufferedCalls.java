package io;

import java.io.*;
import java.util.concurrent.Callable;

/** The calls Buffered* streams make on the stream they wrap, with default and small buffers. */
public class BufferedCalls {
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

    /** n bytes 0,1,2,...; every read returns at most `chunk`; available() reports `avail`. */
    static class Src extends InputStream {
        int pos, n, chunk, avail;
        Src(int n, int chunk, int avail) { this.n = n; this.chunk = chunk; this.avail = avail; }
        public int read() { log.append("r1;"); return pos < n ? pos++ & 0xff : -1; }
        public int read(byte[] b, int off, int len) {
            int k = Math.min(Math.min(len, chunk), n - pos);
            log.append("r" + len + (k <= 0 ? "=-1;" : "=" + k + ";"));
            if (k <= 0) return len == 0 ? 0 : -1;
            for (int i = 0; i < k; i++) b[off + i] = (byte) pos++;
            return k;
        }
        public int available() { log.append("av;"); return avail; }
        public long skip(long s) { log.append("sk" + s + ";"); long k = Math.min(s, n - pos); pos += k; return k; }
        public void close() { log.append("close;"); }
    }

    static class Sink extends OutputStream {
        public void write(int b) { log.append("w1;"); }
        public void write(byte[] b, int off, int len) { log.append("w" + len + ";"); }
        public void flush() { log.append("fl;"); }
        public void close() { log.append("close;"); }
    }

    static class RSrc extends Reader {
        int left, chunk;
        RSrc(int n, int chunk) { left = n; this.chunk = chunk; }
        public int read(char[] c, int off, int len) {
            int k = Math.min(Math.min(len, chunk), left);
            log.append("r" + len + (k <= 0 ? "=-1;" : "=" + k + ";"));
            if (k <= 0) return -1;
            for (int i = 0; i < k; i++) c[off + i] = 'x';
            left -= k;
            return k;
        }
        public boolean ready() { log.append("ready;"); return true; }
        public void close() { log.append("close;"); }
    }

    static class WSink extends Writer {
        public void write(char[] c, int off, int len) { log.append("w" + len + ";"); }
        public void flush() { log.append("fl;"); }
        public void close() { log.append("close;"); }
    }

    public static void main(String[] args) throws Exception {
        byte[] b = new byte[20000];
        t("bis default read()", () -> new BufferedInputStream(new Src(100000, 100000, 0)).read());
        t("bis read 10", () -> new BufferedInputStream(new Src(100000, 100000, 0)).read(b, 0, 10));
        t("bis read 8192 direct", () -> new BufferedInputStream(new Src(100000, 100000, 0)).read(b, 0, 8192));
        t("bis read 9000 direct", () -> new BufferedInputStream(new Src(100000, 100000, 0)).read(b, 0, 9000));
        t("bis read 9000 after mark", () -> { BufferedInputStream s = new BufferedInputStream(new Src(100000, 100000, 0)); s.mark(5); return s.read(b, 0, 9000); });
        t("bis read 8191", () -> new BufferedInputStream(new Src(100000, 100000, 0)).read(b, 0, 8191));
        t("bis short chunks avail 0", () -> new BufferedInputStream(new Src(100, 3, 0), 16).read(b, 0, 10));
        t("bis short chunks avail 5", () -> new BufferedInputStream(new Src(100, 3, 5), 16).read(b, 0, 10));
        t("bis short chunks avail 5, len 40", () -> new BufferedInputStream(new Src(100, 7, 5), 16).read(b, 0, 40));
        t("bis eof", () -> { BufferedInputStream s = new BufferedInputStream(new Src(3, 9, 0), 16); return s.read(b, 0, 10) + " " + s.read(b, 0, 10) + " " + s.read(); });
        t("bis read len 0", () -> new BufferedInputStream(new Src(3, 9, 0), 16).read(b, 0, 0));
        t("bis available", () -> { BufferedInputStream s = new BufferedInputStream(new Src(100, 9, 7), 16); s.read(); return s.available(); });
        t("bis available overflow", () -> { BufferedInputStream s = new BufferedInputStream(new Src(100, 9, Integer.MAX_VALUE), 16); s.read(); return s.available(); });
        t("bis skip empty buffer", () -> new BufferedInputStream(new Src(100, 9, 0), 16).skip(50));
        t("bis skip in buffer", () -> { BufferedInputStream s = new BufferedInputStream(new Src(100, 9, 0), 16); s.read(); return s.skip(50) + " next " + s.read(); });
        t("bis skip with mark", () -> { BufferedInputStream s = new BufferedInputStream(new Src(100, 50, 0), 16); s.mark(30); return s.skip(50) + " next " + s.read(); });
        t("bis skip -3", () -> new BufferedInputStream(new Src(100, 9, 0), 16).skip(-3));
        t("bis mark 20 in 4, read 15, reset", () -> {
            BufferedInputStream s = new BufferedInputStream(new Src(100, 100, 1000), 4);
            s.read(); s.mark(20);
            for (int i = 0; i < 15; i++) s.read();
            s.reset();
            return s.read() + " then read(30)=" + s.read(b, 0, 30) + " b0=" + b[0];
        });
        t("bis mark 2 in 4, read 5, reset", () -> {
            BufferedInputStream s = new BufferedInputStream(new Src(100, 100, 1000), 4);
            s.mark(2);
            for (int i = 0; i < 5; i++) s.read();
            s.reset();
            return s.read();
        });
        t("bis mark 2 in 4, read 4, reset", () -> {
            BufferedInputStream s = new BufferedInputStream(new Src(100, 100, 1000), 4);
            s.mark(2);
            for (int i = 0; i < 4; i++) s.read();
            s.reset();
            return s.read();
        });
        t("bis mark 6 in 4, read 7, reset", () -> {
            BufferedInputStream s = new BufferedInputStream(new Src(100, 100, 1000), 4);
            s.mark(6);
            for (int i = 0; i < 7; i++) s.read();
            s.reset();
            return s.read();
        });
        t("bis reset no mark", () -> { BufferedInputStream s = new BufferedInputStream(new Src(100, 100, 0)); s.reset(); return "?"; });
        t("bis markSupported", () -> new BufferedInputStream(new Src(1, 1, 0)).markSupported());
        t("bis size 0", () -> new BufferedInputStream(new Src(1, 1, 0), 0));
        t("bis close", () -> { BufferedInputStream s = new BufferedInputStream(new Src(1, 1, 0)); s.close(); s.close(); return s.read(); });
        t("bis closed available", () -> { BufferedInputStream s = new BufferedInputStream(new Src(1, 1, 0)); s.close(); return s.available(); });
        t("bis closed skip", () -> { BufferedInputStream s = new BufferedInputStream(new Src(1, 1, 0)); s.close(); return s.skip(1); });

        t("bos small", () -> { BufferedOutputStream o = new BufferedOutputStream(new Sink(), 4); o.write(1); o.write(new byte[3]); o.write(2); return ""; });
        t("bos write = size", () -> { BufferedOutputStream o = new BufferedOutputStream(new Sink(), 4); o.write(1); o.write(new byte[4]); return ""; });
        t("bos write fits", () -> { BufferedOutputStream o = new BufferedOutputStream(new Sink(), 4); o.write(1); o.write(new byte[2]); o.write(new byte[2]); return ""; });
        t("bos flush", () -> { BufferedOutputStream o = new BufferedOutputStream(new Sink(), 4); o.write(1); o.flush(); o.flush(); return ""; });
        t("bos close", () -> { BufferedOutputStream o = new BufferedOutputStream(new Sink(), 4); o.write(1); o.close(); o.close(); return ""; });
        t("bos default size", () -> { BufferedOutputStream o = new BufferedOutputStream(new Sink()); o.write(new byte[8191]); o.write(1); o.write(1); return ""; });
        t("bos size 0", () -> new BufferedOutputStream(new Sink(), 0));

        char[] c = new char[20000];
        t("br read()", () -> new BufferedReader(new RSrc(100000, 100000)).read());
        t("br read 9000 direct", () -> new BufferedReader(new RSrc(100000, 100000)).read(c, 0, 9000));
        t("br read 9000 after mark", () -> { BufferedReader r = new BufferedReader(new RSrc(100000, 100000)); r.mark(5); return r.read(c, 0, 9000); });
        t("br read 100 short chunks", () -> new BufferedReader(new RSrc(100, 3), 16).read(c, 0, 10));
        t("br read len 0", () -> new BufferedReader(new RSrc(100, 3), 16).read(c, 0, 0));
        t("br ready", () -> { BufferedReader r = new BufferedReader(new RSrc(100, 3), 16); boolean a = r.ready(); r.read(); return a + " " + r.ready(); });
        t("br skip", () -> new BufferedReader(new RSrc(100, 7), 16).skip(50));
        t("br skip -1", () -> new BufferedReader(new RSrc(100, 7), 16).skip(-1));
        t("br size 0", () -> new BufferedReader(new RSrc(1, 1), 0));
        t("br close", () -> { BufferedReader r = new BufferedReader(new RSrc(1, 1)); r.close(); r.close(); return r.read(); });
        t("br closed ready", () -> { BufferedReader r = new BufferedReader(new RSrc(1, 1)); r.close(); return r.ready(); });
        t("br closed readLine", () -> { BufferedReader r = new BufferedReader(new RSrc(1, 1)); r.close(); return r.readLine(); });

        t("bw small", () -> { BufferedWriter w = new BufferedWriter(new WSink(), 4); w.write('a'); w.write("bcd"); w.write("e"); return ""; });
        t("bw write char[] = size", () -> { BufferedWriter w = new BufferedWriter(new WSink(), 4); w.write('a'); w.write(new char[4]); return ""; });
        t("bw write String > size", () -> { BufferedWriter w = new BufferedWriter(new WSink(), 4); w.write('a'); w.write("0123456789"); return ""; });
        t("bw flush close", () -> { BufferedWriter w = new BufferedWriter(new WSink(), 4); w.write('a'); w.flush(); w.close(); w.close(); return ""; });
        t("bw closed write", () -> { BufferedWriter w = new BufferedWriter(new WSink(), 4); w.close(); w.write('a'); return ""; });
        t("bw closed flush", () -> { BufferedWriter w = new BufferedWriter(new WSink(), 4); w.close(); w.flush(); return ""; });
        t("bw newLine is line.separator", () -> {
            StringWriter sw = new StringWriter();
            BufferedWriter w = new BufferedWriter(sw); w.newLine(); w.flush();
            return sw.toString().equals(System.getProperty("line.separator"));
        });
        t("bw default size", () -> { BufferedWriter w = new BufferedWriter(new WSink()); w.write(new char[100], 0, 100); w.write(new String(new char[8092])); w.write('x'); return ""; });
        t("bw flushBuffer on close only once", () -> { BufferedWriter w = new BufferedWriter(new WSink(), 4); w.write("ab"); w.close(); return ""; });
        t("bw size 0", () -> new BufferedWriter(new WSink(), 0));
        System.out.println("done");
    }
}
