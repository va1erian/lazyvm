package util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;

/** Base64 wrap(OutputStream) and wrap(InputStream) (docs/design/util-data.md, section 2). */
public class Base64Streams {
    static long seed = 0x5EEDL;

    static int next(int bound) {
        seed ^= seed << 13;
        seed ^= seed >>> 7;
        seed ^= seed << 17;
        return (int) ((seed >>> 1) % bound);
    }

    static byte[] latin1(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < b.length; i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    static String text(byte[] b, int off, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = off; i < off + len; i++) {
            int c = b[i] & 0xff;
            if (c == '\r') sb.append("\\r");
            else if (c == '\n') sb.append("\\n");
            else if (c < 32 || c > 126) sb.append("\\x").append(Integer.toHexString(c));
            else sb.append((char) c);
        }
        return sb.toString();
    }

    static String text(ByteArrayOutputStream bo) {
        byte[] b = bo.toByteArray();
        return text(b, 0, b.length);
    }

    /** An underlying stream that records close, counts writes, and can hand out one byte per read. */
    static class Under extends InputStream {
        final byte[] data;
        int pos;
        final boolean trickle;
        boolean closed;
        int singles;
        int bulks;

        Under(String s, boolean trickle) {
            data = latin1(s);
            this.trickle = trickle;
        }

        public int read() {
            singles++;
            return pos < data.length ? data[pos++] & 0xff : -1;
        }

        public int read(byte[] b, int off, int len) {
            bulks++;
            if (len == 0) return 0;
            if (pos >= data.length) return -1;
            int n = trickle ? 1 : Math.min(len, data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }

        public int available() {
            return 1234;
        }

        public void close() {
            closed = true;
        }
    }

    /** Reads to the end with read() (chunk 0) or read(b, 0, chunk); '.' marks each call's end. */
    static String drain(InputStream in, int chunk) {
        StringBuilder sb = new StringBuilder();
        byte[] b = new byte[Math.max(chunk, 1)];
        try {
            while (sb.length() < 400) {
                int n = chunk == 0 ? in.read() : in.read(b, 0, chunk);
                if (n < 0) return sb.append("|EOF").toString();
                if (chunk == 0) sb.append(text(new byte[] {(byte) n}, 0, 1));
                else sb.append(text(b, 0, n)).append('.');
            }
            return sb.append("|LONG").toString();
        } catch (IOException e) {
            return sb.append("|IOException: ").append(e.getMessage()).toString();
        }
    }

    static String tryWrite(OutputStream os, ByteArrayOutputStream bo, byte[] b, int off, int len) {
        try {
            os.write(b, off, len);
            return "ok " + text(bo);
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    public static void main(String[] args) throws IOException {
        // B14: the encoding stream holds back a partial unit until close.
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        OutputStream os = Base64.getEncoder().wrap(bo);
        os.write('A');
        StringBuilder sb = new StringBuilder("enc stream: 1 [" + text(bo) + "]");
        os.write('B');
        os.write('C');
        sb.append(" 3 [").append(text(bo)).append(']');
        os.write('D');
        os.flush();
        sb.append(" flush [").append(text(bo)).append(']');
        os.write(latin1("EFGHI"), 1, 3);
        sb.append(" arr [").append(text(bo)).append(']');
        os.write(new byte[0]);
        os.write(latin1("xyz"), 3, 0);
        os.close();
        sb.append(" close [").append(text(bo)).append(']');
        System.out.println(sb);
        // B15: after close.
        System.out.println("closed: write " + tryWrite(os, bo, new byte[1], 0, 1) + " | write(int) "
            + (writeInt(os, bo)) + " | len 0 " + tryWrite(os, bo, new byte[1], 0, 0));
        os.flush();
        os.close();
        System.out.println("closed: flush and close again [" + text(bo) + "]");
        // B16: bounds of write(byte[], int, int).
        ByteArrayOutputStream bb = new ByteArrayOutputStream();
        OutputStream ob = Base64.getEncoder().wrap(bb);
        System.out.println("bounds: " + tryWrite(ob, bb, new byte[2], 1, 2) + " | " + tryWrite(ob, bb, new byte[2], -1, 1) + " | "
            + tryWrite(ob, bb, new byte[2], 0, -1) + " | " + tryWrite(ob, bb, new byte[2], 3, 0) + " | " + tryWrite(ob, bb, new byte[2], 2, 0));
        try {
            ob.write(null, 0, 1);
        } catch (NullPointerException e) {
            System.out.println("bounds: null NPE");
        }
        // B14: padding, no padding, MIME lines (separator only before more data).
        String[] tails = {"", "a", "ab", "abc", "abcd", "abcdefghijk"};
        for (String t : tails) {
            Base64.Encoder[] es = {Base64.getEncoder(), Base64.getEncoder().withoutPadding(), Base64.getUrlEncoder(),
                Base64.getMimeEncoder(4, latin1("|")), Base64.getMimeEncoder(8, latin1("\r\n")).withoutPadding()};
            StringBuilder line = new StringBuilder("enc tail '" + t + "':");
            for (Base64.Encoder e : es) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                OutputStream w = e.wrap(o);
                for (int i = 0; i < t.length(); i++) w.write(t.charAt(i) | 0x100);
                line.append(" [").append(text(o)).append("]->");
                w.close();
                line.append('[').append(text(o)).append(']');
            }
            System.out.println(line);
        }
        ByteArrayOutputStream bm = new ByteArrayOutputStream();
        OutputStream om = Base64.getMimeEncoder(4, latin1("|")).wrap(bm);
        om.write(new byte[3]);
        sb = new StringBuilder("mime stream: 3 [" + text(bm) + "]");
        om.write(new byte[3]);
        sb.append(" 6 [").append(text(bm)).append(']');
        om.write(1);
        sb.append(" 7 [").append(text(bm)).append(']');
        om.close();
        System.out.println(sb.append(" close [").append(text(bm)).append(']'));
        // B14a: a line length of 1 to 3 gives arrays one unbroken line, but the stream puts the
        // separator once before its first character; 0 and below add nothing.
        sb = new StringBuilder("mime short lines:");
        for (int len : new int[] {-1, 0, 1, 3, 4}) {
            for (int n : new int[] {0, 1, 4}) {
                Base64.Encoder e = Base64.getMimeEncoder(len, latin1("|"));
                if (n == 4) e = e.withoutPadding();
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                OutputStream w = e.wrap(o);
                w.write(new byte[n]);
                w.close();
                sb.append(' ').append(len).append('/').append(n).append(" [").append(text(o)).append("] [")
                    .append(e.encodeToString(new byte[n])).append(']');
            }
        }
        System.out.println(sb);
        // B14: close closes the wrapped stream.
        final boolean[] closed = {false};
        OutputStream oc = Base64.getEncoder().wrap(new OutputStream() {
            public void write(int b) { }
            public void close() { closed[0] = true; }
        });
        oc.close();
        System.out.println("enc close closes underlying " + closed[0]);
        // Fuzz B14: random data written in random pieces equals encodeToString.
        int same = 0;
        for (int round = 0; round < 200; round++) {
            byte[] data = new byte[next(120)];
            for (int i = 0; i < data.length; i++) data[i] = (byte) next(256);
            Base64.Encoder e = round % 3 == 0 ? Base64.getMimeEncoder(4 * (1 + next(5)), latin1("\n"))
                : round % 3 == 1 ? Base64.getUrlEncoder().withoutPadding() : Base64.getMimeEncoder();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            OutputStream w = e.wrap(o);
            for (int i = 0; i < data.length;) {
                int n = Math.min(next(9), data.length - i);
                if (n == 1) w.write(data[i]); else w.write(data, i, n);
                i += n;
            }
            w.close();
            if (text(o).equals(text(latin1(e.encodeToString(data)), 0, latin1(e.encodeToString(data)).length))) same++;
        }
        System.out.println("fuzz enc stream same " + same);

        // B17-B20: the decoding stream.
        String[] srcs = {"", "QUJDREVG", "QUJDREVGRw==", "QUJDREVGRw", "QUJDREVGR0g=", "QUJDREVGR0g", "QUJD\r\nREVG", "QUJD!REVG",
            "QUJD=REVG", "QUJDR", "QUJDRA=", "QUJDR=", "QUJDR==", "QUJDRA=x", "QUJDRA==QQ==", "QUJD====", "QUI=x", "QQ===",
            "QUJDREU==", "Q", "=", "QQ=", "QUJD\n", "\nQUJD", "-_-_+/", "+/+/-_", "QUJDÿREVG"};
        int[] chunks = {0, 1, 3, 64};
        for (String s : srcs) {
            StringBuilder line = new StringBuilder("dec stream '" + text(latin1(s), 0, s.length()) + "':");
            for (int c : chunks) {
                line.append(" B").append(c).append('=').append(drain(Base64.getDecoder().wrap(new Under(s, false)), c));
            }
            line.append(" M64=").append(drain(Base64.getMimeDecoder().wrap(new Under(s, false)), 64));
            line.append(" U64=").append(drain(Base64.getUrlDecoder().wrap(new Under(s, false)), 64));
            line.append(" trickle=").append(drain(Base64.getMimeDecoder().wrap(new Under(s, true)), 64));
            System.out.println(line);
        }
        // B17: the source is read with read(), one byte at a time, and only as far as the units a
        // call needs: never past the padding, never ahead of the request.
        StringBuilder reads = new StringBuilder("dec source reads:");
        for (String s : new String[] {"QQ==QUJD", "QUJDREVGR0hJ", "QUI=QUJD"}) {
            for (int want : new int[] {1, 3, 6, 64}) {
                Under src = new Under(s, false);
                int got = Base64.getDecoder().wrap(src).read(new byte[64], 0, want);
                reads.append(' ').append(s).append('/').append(want).append(" got ").append(got).append(" pos ")
                    .append(src.pos).append(" singles ").append(src.singles).append(" bulks ").append(src.bulks);
            }
        }
        Under ms = new Under("QUJD\r\nREVG\r\nR0hJ", false);
        int mg = Base64.getMimeDecoder().wrap(ms).read(new byte[64], 0, 3);
        System.out.println(reads.append(" mime/3 got ").append(mg).append(" pos ").append(ms.pos));
        // B19: available() is the wrapped stream's; reads after close fail.
        Under u = new Under("QUJDREVG", false);
        InputStream in = Base64.getDecoder().wrap(u);
        System.out.println("available " + in.available() + " read " + (char) in.read() + " available " + in.available()
            + " read0 " + in.read(new byte[3], 0, 0) + " markSupported " + in.markSupported() + " skip " + in.skip(2) + " then " + (char) in.read());
        try {
            in.read(new byte[3], 2, 2);
        } catch (IndexOutOfBoundsException e) {
            System.out.println("read bounds " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        try {
            in.read(null, 0, 1);
        } catch (NullPointerException e) {
            System.out.println("read null NPE");
        }
        in.close();
        System.out.println("close closes underlying " + u.closed);
        try { in.read(); } catch (IOException e) { System.out.println("read after close: " + e.getMessage()); }
        try { in.read(new byte[2], 0, 2); } catch (IOException e) { System.out.println("read(b) after close: " + e.getMessage()); }
        try { in.available(); } catch (IOException e) { System.out.println("available after close: " + e.getMessage()); }
        in.close();
        System.out.println("close twice ok");
        InputStream eof = Base64.getDecoder().wrap(new Under("QQ==", false));
        System.out.println("eof " + eof.read() + " " + eof.read() + " " + eof.read() + " " + eof.read(new byte[1], 0, 1));
        // Fuzz B17: random data, encoded, read back in chunks of 1, 3 or all-at-once (see section 4 on other sizes).
        int back = 0;
        for (int round = 0; round < 200; round++) {
            byte[] data = new byte[next(100)];
            for (int i = 0; i < data.length; i++) data[i] = (byte) next(256);
            boolean mime = round % 2 == 0;
            String s = (mime ? Base64.getMimeEncoder(4 + 4 * next(10), latin1("\r\n")) : Base64.getEncoder()).encodeToString(data);
            InputStream r = (mime ? Base64.getMimeDecoder() : Base64.getDecoder()).wrap(new Under(s, round % 5 == 0));
            int c = round % 3 == 0 ? 1 : round % 3 == 1 ? 3 : 4096;
            ByteArrayOutputStream got = new ByteArrayOutputStream();
            byte[] buf = new byte[c];
            for (int n; (n = r.read(buf, 0, c)) >= 0;) got.write(buf, 0, n);
            if (java.util.Arrays.equals(got.toByteArray(), data)) back++;
        }
        System.out.println("fuzz dec stream back " + back);
        System.out.println("done");
    }

    static String writeInt(OutputStream os, ByteArrayOutputStream bo) {
        try {
            os.write(65);
            return "ok " + text(bo);
        } catch (IOException e) {
            return "IOException: " + e.getMessage();
        }
    }
}
