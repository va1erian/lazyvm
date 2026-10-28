package util;

import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/** Base64 encoders and decoders on byte arrays and strings (docs/design/util-data.md, section 1). */
public class Base64s {
    static long seed = 0x1234ABCDL;

    /** A fixed xorshift generator, so the fuzz lines do not depend on java.util.Random. */
    static int next(int bound) {
        seed ^= seed << 13;
        seed ^= seed >>> 7;
        seed ^= seed << 17;
        return (int) ((seed >>> 1) % bound);
    }

    static String show(byte[] b) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < b.length; i++) sb.append(i == 0 ? "" : " ").append(b[i]);
        return sb.append(']').toString();
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r') sb.append("\\r");
            else if (c == '\n') sb.append("\\n");
            else if (c < 32 || c > 126) sb.append("\\u").append(Integer.toHexString(c));
            else sb.append(c);
        }
        return sb.toString();
    }

    static String err(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    static String dec(Base64.Decoder d, String s) {
        try {
            return show(d.decode(s));
        } catch (Exception e) {
            return err(e);
        }
    }

    static String enc(Base64.Encoder e, byte[] b) {
        try {
            return esc(e.encodeToString(b));
        } catch (Exception x) {
            return err(x);
        }
    }

    static String mime(int len, byte[] sep, int n) {
        try {
            return esc(Base64.getMimeEncoder(len, sep).encodeToString(new byte[n]));
        } catch (Exception x) {
            return err(x);
        }
    }

    static String decInto(Base64.Decoder d, String s, int n) {
        byte[] dst = new byte[n];
        Arrays.fill(dst, (byte) 7);
        try {
            int k = d.decode(latin1(s), dst);
            return k + " " + show(dst);
        } catch (Exception e) {
            return e.getMessage() + " " + show(dst);
        }
    }

    static String encInto(Base64.Encoder e, byte[] src, int n) {
        byte[] dst = new byte[n];
        Arrays.fill(dst, (byte) '.');
        try {
            int k = e.encode(src, dst);
            return k + " " + esc(text(dst));
        } catch (Exception x) {
            return x.getMessage();
        }
    }

    /** ISO-8859-1 bytes without the charset machinery. */
    static byte[] latin1(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < b.length; i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    static String text(byte[] b) {
        char[] c = new char[b.length];
        for (int i = 0; i < b.length; i++) c[i] = (char) (b[i] & 0xff);
        return new String(c);
    }

    /** An error message with its trailing hex or index replaced by '#', to group outcomes. */
    static String kind(String r) {
        int sp = r.lastIndexOf(' ');
        return r.startsWith("[") ? "ok" : sp < 0 ? r : r.substring(0, sp) + " #";
    }

    static int hash(String s) {
        int h = 7;
        for (int i = 0; i < s.length(); i++) h = h * 31 + s.charAt(i);
        return h;
    }

    public static void main(String[] args) {
        Base64.Encoder be = Base64.getEncoder(), ue = Base64.getUrlEncoder(), me = Base64.getMimeEncoder();
        Base64.Decoder bd = Base64.getDecoder(), ud = Base64.getUrlDecoder(), md = Base64.getMimeDecoder();
        // B1, B2: alphabets, padding, every tail length.
        byte[] all = {(byte) 0xfb, (byte) 0xff, (byte) 0xbf, 0, 1, 2, (byte) 0x80, 0x7f};
        for (int n = 0; n <= all.length; n++) {
            byte[] p = Arrays.copyOf(all, n);
            System.out.println("enc " + n + ": " + enc(be, p) + " | " + enc(ue, p) + " | " + enc(me, p)
                + " | " + enc(be.withoutPadding(), p) + " | " + enc(ue.withoutPadding(), p));
        }
        System.out.println("encode(byte[]) " + text(be.encode(latin1("hello"))) + " len0 " + be.encode(new byte[0]).length
            + " mime0 " + me.encode(new byte[0]).length);
        // B3: MIME lines of 76 with CRLF, no separator after the last line.
        System.out.println("mime 57 " + me.encodeToString(new byte[57]).length() + " 58 " + esc(me.encodeToString(new byte[58])).substring(70));
        System.out.println("mime 114 " + esc(me.encodeToString(new byte[114])).substring(150) + " 115 " + me.encodeToString(new byte[115]).length());
        // B4: line length rounds down to a multiple of 4; below 4 means one line.
        int[] lens = {-5, -1, 0, 1, 3, 4, 5, 7, 8, 11, 12, 76, 77, 79, 80, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int len : lens) System.out.println("mime len " + len + ": " + mime(len, new byte[] {'\n'}, 13));
        // B5: separator bytes from the basic alphabet or '=' are refused, with the byte in hex.
        for (int b : new int[] {'A', 'Z', 'a', 'z', '0', '9', '+', '/', '=', '-', '_', '\r', '\n', ' ', 0, 0x80, 0xff, '.', '!'}) {
            System.out.println("sep " + Integer.toHexString(b) + ": " + mime(4, new byte[] {(byte) b}, 5));
        }
        System.out.println("sep empty: " + mime(4, new byte[0], 7) + " | two: " + mime(8, latin1("<>"), 13)
            + " | bad second: " + mime(8, new byte[] {'\n', 'q'}, 1) + " | bad, len 0: " + mime(0, new byte[] {'A'}, 1));
        System.out.println("sep null: " + mime(4, null, 1) + " | null len 0: " + mime(0, null, 1) + " | null len -1: " + mime(-1, null, 1));
        System.out.println("mime nopad: " + enc(Base64.getMimeEncoder(8, latin1("!!")).withoutPadding(), new byte[13])
            + " | mime alphabet: " + enc(Base64.getMimeEncoder(4, new byte[] {'-'}), new byte[] {(byte) 0xfb, (byte) 0xff, (byte) 0xfe, (byte) 0xfb}));
        // B6: encode into an array needs room for the whole output.
        for (int n = 4; n <= 10; n++) System.out.println("encInto " + n + ": " + encInto(be, new byte[] {1, 2, 3, 4}, n)
            + " | nopad " + encInto(be.withoutPadding(), new byte[] {1, 2, 3, 4}, n)
            + " | mime4 " + encInto(Base64.getMimeEncoder(4, "\n".getBytes()), new byte[] {1, 2, 3, 4, 5, 6}, n));
        System.out.println("encInto empty " + encInto(be, new byte[0], 0));
        // B7: null arguments; the factories return shared instances.
        try { be.encode((byte[]) null); } catch (NullPointerException e) { System.out.println("encode null NPE"); }
        try { be.encodeToString(null); } catch (NullPointerException e) { System.out.println("encodeToString null NPE"); }
        try { bd.decode((String) null); } catch (NullPointerException e) { System.out.println("decode null NPE"); }
        try { bd.decode((byte[]) null); } catch (NullPointerException e) { System.out.println("decode bytes null NPE"); }
        try { be.wrap(null); } catch (NullPointerException e) { System.out.println("wrap null NPE"); }
        try { bd.wrap(null); } catch (NullPointerException e) { System.out.println("dec wrap null NPE"); }
        System.out.println("singletons " + (Base64.getEncoder() == Base64.getEncoder()) + " " + (Base64.getMimeDecoder() == Base64.getMimeDecoder())
            + " " + (Base64.getUrlEncoder() == Base64.getUrlEncoder()));

        // B8-B12: decoding, the three decoders side by side.
        String[] ins = {"", "A", "=", "\u0000", "!", "AA", "AAA", "AAAA", "AA==", "AAA=", "AA=", "AA=A", "A===", "A=", "AAA==", "AAAA=",
            "AAAA==", "==", "===", "====", "AA==AA==", "QUI=QUI=", "QQ", "QUI", "QUJD", "QUJDQ", "QUJDQUJDQ", "Q=Q=", "AB==", "AE==",
            "AAB=", "AAE=", "AP//", "-_", "+/", "+/+/", "-_-_", "A A", "A\nA==", "QUJD\r\nQUJD", "QUJDÿ", "QUJDĀ", "AA==\n",
            "AA==x", "AA==!", "AA===", "AAAA\n=", "QUJD!", "Q!JD", "!QUJD", "QUJD\n", "\nQUJD", "QU=JD", "QQ=\n=", "QQ\n==", "QUJ\n=",
            "QQ==QUJD", "=A", "QUJD====", "Q\u0080UJD", "QUJ\u007fD", "\r\n", "QUJD\r\n\r\n", "QUJDQ!!!", "QUJDQQ\n=="};
        for (String s : ins) System.out.println("dec " + esc(s) + " | B " + dec(bd, s) + " | U " + dec(ud, s) + " | M " + dec(md, s));
        // B13: decoding into an array: the size check, and bytes written before an error.
        String[] into = {"QUJD\n\n\n\n", "QUJD====", "QUI=\n\n", "QQ==", "QQ", "QUI", "QUJDQQ", "QUJD\nQQ==", "Q\nQ", "QUJD!!!!!!!!",
            "QQ==QUJD", "QUJDQ", "Q!U!J!D!", "QU=JD", "QQ=", "A", "=A", "AA!", "!", "QUJD=", "QUJDQUJD!", "QUJDQUJDQ", "AA==AA=="};
        for (String s : into) {
            StringBuilder sb = new StringBuilder("into " + esc(s) + ":");
            for (int n = 0; n <= 6; n++) sb.append(" B").append(n).append('=').append(decInto(bd, s, n));
            System.out.println(sb);
            sb = new StringBuilder("into " + esc(s) + ":");
            for (int n = 0; n <= 6; n++) sb.append(" M").append(n).append('=').append(decInto(md, s, n));
            System.out.println(sb);
        }
        System.out.println("into big " + decInto(bd, "QUJDQUJDQ", 9) + " | " + decInto(bd, "QUJDQUJD!", 9) + " | empty " + decInto(bd, "", 0));
        // B9: decode(String) maps chars through ISO-8859-1, so U+0100 becomes '?'.
        System.out.println("latin1 " + dec(md, "QUĀJD") + " | " + dec(bd, "€€"));

        // Fuzz B1-B4: round trips of random data through every encoder; a checksum of the text.
        int sum = 0, ok = 0;
        for (int round = 0; round < 400; round++) {
            byte[] b = new byte[next(200)];
            for (int i = 0; i < b.length; i++) b[i] = (byte) next(256);
            Base64.Encoder[] es = {be, ue, me, be.withoutPadding(), ue.withoutPadding(),
                Base64.getMimeEncoder(4 + next(40), new byte[] {'\n'}), Base64.getMimeEncoder(next(90), latin1("**"))};
            Base64.Decoder[] ds = {bd, ud, md, bd, ud, md, md};
            for (int k = 0; k < es.length; k++) {
                String t = es[k].encodeToString(b);
                sum = sum * 31 + hash(t);
                if (Arrays.equals(ds[k].decode(t), b) && Arrays.equals(ds[k].decode(latin1(t)), b)) ok++;
            }
        }
        System.out.println("fuzz encode sum " + sum + " roundtrips " + ok);
        // Fuzz B8-B13: random strings over the alphabet, '=' and junk; every outcome folded into a checksum.
        String chars = "AQgw+/-_=\n\r !ÿ";
        TreeMap<String, Integer> outcomes = new TreeMap<String, Integer>();
        int dsum = 0;
        for (int round = 0; round < 3000; round++) {
            StringBuilder s = new StringBuilder();
            int len = next(14);
            for (int i = 0; i < len; i++) s.append(chars.charAt(next(next(4) == 0 ? chars.length() : 4)));
            Base64.Decoder[] ds = {bd, ud, md};
            for (int k = 0; k < 3; k++) {
                String r = dec(ds[k], s.toString());
                String key = kind(r);
                Integer c = outcomes.get(key);
                outcomes.put(key, c == null ? 1 : c + 1);
                dsum = dsum * 31 + hash(r);
                dsum = dsum * 31 + hash(decInto(ds[k], s.toString(), next(10)));
            }
        }
        System.out.println("fuzz decode sum " + dsum);
        for (Map.Entry<String, Integer> e : outcomes.entrySet()) System.out.println("  " + e.getKey() + " x" + e.getValue());
        System.out.println("done");
    }
}
