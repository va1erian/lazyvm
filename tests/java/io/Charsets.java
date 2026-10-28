package io;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** UTF-8 decoding of malformed input: what new String and InputStreamReader produce, also when the
 *  bytes arrive one at a time and when a sequence straddles the reader's 8192-byte reads. */
public class Charsets {
    static byte[] hex(String s) {
        String[] p = s.trim().split(" +");
        byte[] b = new byte[p.length];
        for (int i = 0; i < p.length; i++) b[i] = (byte) Integer.parseInt(p[i], 16);
        return b;
    }

    static String chars(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (sb.length() > 0) sb.append(' ');
            if (c == 0xFFFD) sb.append('?');
            else if (c >= 0x21 && c < 0x7f) sb.append(c);
            else sb.append(String.format("%04X", (int) c));
        }
        return sb.toString();
    }

    /** Delivers one byte per read call. */
    static class Trickle extends InputStream {
        final byte[] b; int pos;
        Trickle(byte[] b) { this.b = b; }
        public int read() { return pos < b.length ? b[pos++] & 0xff : -1; }
        public int read(byte[] d, int off, int len) {
            if (len == 0) return 0;
            if (pos >= b.length) return -1;
            d[off] = b[pos++];
            return 1;
        }
    }

    static String drain(Reader r) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] c = new char[3];
        int n;
        while ((n = r.read(c)) >= 0) sb.append(c, 0, n);
        return sb.toString();
    }

    static final String[] CASES = {
        "41", "C3 A9", "E2 82 AC", "F0 9F 98 80", "EF BB BF 41", "F4 8F BF BF",
        "80", "BF", "80 80 41", "C0 80", "C1 BF", "C2 7F", "C2 C0", "C3",
        "E0 80 80", "E0 9F BF", "E0 A0 80", "E0 41", "E0 80", "E0 A0", "E0 A0 41", "E1 80", "E1 41 41",
        "E2 82", "E2 82 41", "E2 C3 A9", "ED 9F BF", "ED A0 80", "ED BF BF", "ED A0 80 ED B0 80", "ED A0",
        "EE 80 80", "EF BF BF", "EF BF BE",
        "F0 80 80 80", "F0 8F BF BF", "F0 90 80 80", "F0 9F", "F0 9F 98", "F0 9F 98 41", "F0 9F 41 41",
        "F0 41", "F0 80", "F0 80 80", "F4 90 80 80", "F4 8F", "F4 90", "F1 80 80 C3 A9",
        "F5 80 80 80", "F7 BF BF BF", "F8 88 80 80 80", "FC 84 80 80 80 80", "FE", "FF", "FE FF",
        "41 C3 C3 A9 42", "F0 9F 98 F0 9F 98 80", "C3 80 80", "E2 82 AC AC", "ED 80 41",
    };

    public static void main(String[] args) throws Exception {
        for (String c : CASES) {
            byte[] b = hex(c);
            String s = new String(b, StandardCharsets.UTF_8);
            String isr = drain(new InputStreamReader(new ByteArrayInputStream(b), StandardCharsets.UTF_8));
            String trickle = drain(new InputStreamReader(new Trickle(b), "UTF-8"));
            System.out.println("[" + c + "] -> " + chars(s)
                + (isr.equals(s) ? "" : " | reader: " + chars(isr))
                + (trickle.equals(s) ? "" : " | trickle: " + chars(trickle)));
        }
        // A sequence that starts 1, 2 or 3 bytes before the end of the reader's first 8192-byte read.
        for (String c : new String[]{"E2 82 AC", "F0 9F 98 80", "E2 82 41", "F0 9F 41", "ED A0 80", "C3"}) {
            for (int back = 1; back <= 3; back++) {
                byte[] seq = hex(c);
                byte[] b = new byte[8192 - back + seq.length + 1];
                java.util.Arrays.fill(b, (byte) 'a');
                System.arraycopy(seq, 0, b, 8192 - back, seq.length);
                b[b.length - 1] = 'z';
                String s = new String(b, StandardCharsets.UTF_8);
                String isr = drain(new InputStreamReader(new ByteArrayInputStream(b), StandardCharsets.UTF_8));
                int from = 8192 - back - 1;
                System.out.println("edge -" + back + " [" + c + "] -> " + chars(s.substring(from))
                    + (isr.equals(s) ? "" : " | reader: " + chars(isr.substring(from))));
            }
        }
        // Decoding via a byte offset/length window and the String(byte[]) default charset (UTF-8 here).
        byte[] w = hex("41 E2 82 AC 42");
        System.out.println("window 1,2: " + chars(new String(w, 1, 2, StandardCharsets.UTF_8)));
        System.out.println("window 1,3: " + chars(new String(w, 1, 3, "UTF-8")));
        System.out.println("default charset: " + chars(new String(w)));
        try {
            new String(w, 2, 9, StandardCharsets.UTF_8);
        } catch (IndexOutOfBoundsException e) {
            System.out.println("window 2,9: " + e.getClass().getName() + ": " + e.getMessage());
        }
        System.out.println("done");
    }
}
