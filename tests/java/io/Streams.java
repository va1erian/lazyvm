package io;

import java.io.*;
import java.util.concurrent.Callable;

/** Default methods of InputStream, OutputStream, Reader and Writer, observed through subclasses
 *  that implement only the abstract methods and log every call. */
public class Streams {
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

    /** Only read(); throws at index failAt if set. */
    static class In extends InputStream {
        int pos, n, failAt = -1;
        In(int n) { this.n = n; }
        public int read() throws IOException {
            log.append("r");
            if (pos == failAt) { failAt = -1; throw new IOException("boom"); }
            return pos < n ? (pos++ & 0xff) : -1;
        }
    }

    /** Logs read(byte[],int,int) lengths. */
    static class Chunky extends InputStream {
        int left;
        Chunky(int n) { left = n; }
        public int read() { return left-- > 0 ? 7 : -1; }
        public int read(byte[] b, int off, int len) {
            log.append("[" + off + "," + len + "]");
            if (left <= 0) return -1;
            int k = Math.min(len, Math.min(left, 1000));
            left -= k;
            return k;
        }
    }

    static class Out extends OutputStream {
        public void write(int b) { log.append("w" + b); }
    }

    static class Rd extends Reader {
        int left;
        Rd(int n) { left = n; }
        public int read(char[] c, int off, int len) {
            log.append("[" + off + "," + len + "]");
            if (left <= 0) return -1;
            int k = Math.min(len, left);
            for (int i = 0; i < k; i++) c[off + i] = 'a';
            left -= k;
            return k;
        }
        public void close() { log.append("close"); }
    }

    static class Wr extends Writer {
        public void write(char[] c, int off, int len) { log.append("[" + (len > 20 ? len + " chars" : new String(c, off, len)) + "/" + c.length + "]"); }
        public void flush() { log.append("flush"); }
        public void close() { log.append("close"); }
    }

    public static void main(String[] args) throws Exception {
        byte[] b = new byte[10];
        t("read(b) 3 avail", () -> new In(3).read(b));
        t("read(b) empty", () -> new In(0).read(b));
        t("read(b,0,0)", () -> new In(3).read(b, 0, 0));
        t("read(b,10,0)", () -> new In(3).read(b, 10, 0));
        t("read(b,11,0)", () -> new In(3).read(b, 11, 0));
        t("read(b,-1,1)", () -> new In(3).read(b, -1, 1));
        t("read(b,5,6)", () -> new In(3).read(b, 5, 6));
        t("read(null,0,0)", () -> new In(3).read(null, 0, 0));
        t("read fail at 2", () -> { In i = new In(5); i.failAt = 2; return i.read(b) + " then " + i.read(b); });
        t("read fail at 0", () -> { In i = new In(5); i.failAt = 0; return i.read(b); });
        t("skip 5 of 3", () -> new In(3).skip(5));
        t("skip -1", () -> new In(3).skip(-1));
        t("skip 0", () -> new In(3).skip(0));
        t("skip 5000 chunky", () -> new Chunky(10000).skip(5000));
        t("skip 3000 of 2500 chunky", () -> new Chunky(2500).skip(3000));
        t("available", () -> new In(3).available());
        t("markSupported", () -> new In(3).markSupported());
        t("reset", () -> { In i = new In(3); i.mark(10); i.reset(); return "?"; });
        t("close twice", () -> { In i = new In(3); i.close(); i.close(); return i.read(); });

        t("out write(b)", () -> { new Out().write(new byte[]{1, 2, 3}); return ""; });
        t("out write(b,1,1)", () -> { new Out().write(new byte[]{1, 2, 3}, 1, 1); return ""; });
        t("out write(b,1,3)", () -> { new Out().write(new byte[]{1, 2, 3}, 1, 3); return ""; });
        t("out write(b,3,0)", () -> { new Out().write(new byte[]{1, 2, 3}, 3, 0); return ""; });
        t("out write(b,4,0)", () -> { new Out().write(new byte[]{1, 2, 3}, 4, 0); return ""; });
        t("out write(null,0,0)", () -> { new Out().write(null, 0, 0); return ""; });
        t("out write 300", () -> { new Out().write(300); return ""; });
        t("out flush close", () -> { Out o = new Out(); o.flush(); o.close(); o.write(1); return ""; });

        char[] c = new char[10];
        t("reader read()", () -> new Rd(3).read());
        t("reader read() eof", () -> new Rd(0).read());
        t("reader read(c)", () -> new Rd(3).read(c));
        t("reader skip 20000", () -> new Rd(30000).skip(20000));
        t("reader skip 5 of 3", () -> new Rd(3).skip(5));
        t("reader skip -1", () -> new Rd(3).skip(-1));
        t("reader skip 0", () -> new Rd(3).skip(0));
        t("reader ready", () -> new Rd(3).ready());
        t("reader markSupported", () -> new Rd(3).markSupported());
        t("reader mark", () -> { new Rd(3).mark(1); return "?"; });
        t("reader reset", () -> { new Rd(3).reset(); return "?"; });
        t("reader read(CharBuffer)", () -> { java.nio.CharBuffer cb = java.nio.CharBuffer.allocate(4); int r = new Rd(9).read(cb); return r + " " + cb.position(); });

        t("writer write(int)", () -> { new Wr().write(0x10041); return ""; });
        t("writer write(String)", () -> { new Wr().write("hello"); return ""; });
        t("writer write(String,1,3)", () -> { new Wr().write("hello", 1, 3); return ""; });
        t("writer write(String,1,9)", () -> { new Wr().write("hello", 1, 9); return ""; });
        t("writer write(char[])", () -> { new Wr().write(new char[]{'x', 'y'}); return ""; });
        for (int n : new int[]{1024, 1025}) {
            String q = new String(new char[n]).replace('\0', 'q');
            t("writer write String " + n, () -> { new Wr().write(q); return ""; });
        }
        t("writer append null", () -> { new Wr().append(null); return ""; });
        t("writer append csq 1 3", () -> { new Wr().append("hello", 1, 3); return ""; });
        t("writer append null 1 3", () -> { new Wr().append(null, 1, 3); return ""; });
        t("writer append char", () -> { new Wr().append('z'); return ""; });
        t("writer append returns this", () -> { Wr w = new Wr(); return w.append('a') == w; });

        // ByteArray streams
        ByteArrayInputStream bi = new ByteArrayInputStream(new byte[]{1, 2, 3, 4, 5, 6}, 1, 4);
        t("bais available", () -> bi.available());
        t("bais read", () -> bi.read());
        t("bais mark/skip/reset", () -> { bi.mark(0); long s = bi.skip(10); bi.reset(); return s + " " + bi.read(); });
        t("bais skip -5", () -> bi.skip(-5));
        t("bais read(b,0,0) at eof", () -> { bi.skip(9); return bi.read(new byte[2], 0, 0) + " " + bi.read(new byte[2], 0, 1); });
        t("bais markSupported", () -> bi.markSupported());
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        t("baos", () -> { bo.write(0x141); bo.write("é!".getBytes("UTF-8")); return bo.size() + " " + bo.toString("UTF-8") + " " + bo.toByteArray().length; });
        t("baos new(-1)", () -> new ByteArrayOutputStream(-1));
        t("baos reset", () -> { bo.reset(); return bo.size() + "[" + bo + "]"; });
        t("baos writeTo", () -> { bo.write(65); bo.writeTo(new Out()); return ""; });
        System.out.println("done");
    }
}
