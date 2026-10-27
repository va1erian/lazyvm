package io;

import java.io.*;
import java.util.concurrent.Callable;

/** BufferedReader.readLine terminators at every buffer edge, read() after a '\r', and mark/reset
 *  limits for BufferedReader and the char/byte array streams. */
public class ReadLines {
    static void t(String label, Callable<Object> c) {
        String r;
        try {
            r = String.valueOf(c.call());
        } catch (Throwable e) {
            r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        System.out.println(label + ": " + r);
    }

    static String esc(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    static String lines(String text, int size) throws IOException {
        BufferedReader r = new BufferedReader(new StringReader(text), size);
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(esc(l)).append(' ');
        return sb.append(esc(r.readLine())).toString();
    }

    /** Alternates readLine() and read(), to show where a '\n' after '\r' is swallowed. */
    static String mixed(String text, int size) throws IOException {
        BufferedReader r = new BufferedReader(new StringReader(text), size);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i % 2 == 0) sb.append(esc(r.readLine())).append(' ');
            else { int c = r.read(); sb.append(c < 0 ? "-1" : esc(String.valueOf((char) c))).append(' '); }
        }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String[] texts = {"", "\n", "\r", "\r\n", "\n\r", "a", "a\n", "a\r", "a\r\n", "a\n\nb",
            "a\r\rb", "a\r\n\r\nb", "a\nb\r\nc\rd\r\n\ne", "\r\r\n\n", "ab\u2028cd\u0085ef\fgh"};
        for (String s : texts) {
            for (int size = 1; size <= 4; size++) System.out.println("lines " + esc(s) + " /" + size + ": " + lines(s, size));
            System.out.println("lines " + esc(s) + " /8192: " + lines(s, 8192));
        }
        System.out.println("mixed a\\rb /2: " + mixed("a\r\nb\r\nc", 2));
        System.out.println("mixed a\\rb /8192: " + mixed("a\r\nb\r\nc", 8192));
        System.out.println("mixed \\r\\n\\r /1: " + mixed("\r\n\r\nx\r", 1));
        t("read(c) after \\r line", () -> { BufferedReader r = new BufferedReader(new StringReader("a\r\nbc")); r.readLine(); char[] c = new char[5]; int n = r.read(c, 0, 5); return n + " " + esc(new String(c, 0, n)); });
        t("skip after \\r line", () -> { BufferedReader r = new BufferedReader(new StringReader("a\r\nbc")); r.readLine(); return r.skip(1) + " " + (char) r.read(); });
        t("ready after \\r line at \\n end", () -> { BufferedReader r = new BufferedReader(new StringReader("a\r\n")); r.readLine(); return r.ready() + " " + r.read(); });
        t("read() of \\r\\n", () -> { BufferedReader r = new BufferedReader(new StringReader("\r\n")); return r.read() + " " + r.read() + " " + r.read(); });
        t("long line", () -> { StringBuilder sb = new StringBuilder(); for (int i = 0; i < 20000; i++) sb.append((char) ('a' + i % 26)); return lines(sb + "\nz", 8192).length(); });

        t("br mark -1", () -> { new BufferedReader(new StringReader("abc")).mark(-1); return "?"; });
        t("br reset unmarked", () -> { new BufferedReader(new StringReader("abc")).reset(); return "?"; });
        t("br mark 2 read 2 reset", () -> { BufferedReader r = new BufferedReader(new StringReader("abcdef"), 2); r.mark(2); r.read(); r.read(); r.reset(); return (char) r.read(); });
        t("br mark 2 read 3 reset /2", () -> { BufferedReader r = new BufferedReader(new StringReader("abcdef"), 2); r.mark(2); r.read(); r.read(); r.read(); r.reset(); return (char) r.read(); });
        t("br mark 2 read 3 reset /8192", () -> { BufferedReader r = new BufferedReader(new StringReader("abcdef")); r.mark(2); r.read(); r.read(); r.read(); r.reset(); return (char) r.read(); });
        t("br mark 5 /2 read 5 reset", () -> { BufferedReader r = new BufferedReader(new StringReader("abcdefgh"), 2); r.mark(5); for (int i = 0; i < 5; i++) r.read(); r.reset(); return (char) r.read(); });
        t("br mark 5 /2 read 6 reset", () -> { BufferedReader r = new BufferedReader(new StringReader("abcdefgh"), 2); r.mark(5); for (int i = 0; i < 6; i++) r.read(); r.reset(); return (char) r.read(); });
        t("br reset twice", () -> { BufferedReader r = new BufferedReader(new StringReader("abcdef")); r.read(); r.mark(9); r.read(); r.reset(); r.read(); r.reset(); return (char) r.read(); });
        t("br mark across \\r\\n", () -> { BufferedReader r = new BufferedReader(new StringReader("a\r\nb")); r.readLine(); r.mark(5); int x = r.read(); r.reset(); return x + " " + r.read(); });
        t("br markSupported", () -> new BufferedReader(new StringReader("")).markSupported());
        t("br mark 0 read 1 reset /8192", () -> { BufferedReader r = new BufferedReader(new StringReader("abc")); r.mark(0); r.read(); r.reset(); return (char) r.read(); });

        t("StringReader mark -1", () -> { new StringReader("abc").mark(-1); return "?"; });
        t("StringReader reset unmarked", () -> { StringReader r = new StringReader("abc"); r.read(); r.reset(); return (char) r.read(); });
        t("StringReader skip -2", () -> { StringReader r = new StringReader("abc"); r.read(); r.read(); return r.skip(-2) + " " + (char) r.read(); });
        t("StringReader skip -9", () -> { StringReader r = new StringReader("abc"); r.read(); return r.skip(-9) + " " + (char) r.read(); });
        t("StringReader skip at eof", () -> { StringReader r = new StringReader("ab"); r.skip(5); return r.skip(5) + " " + r.read(); });
        t("StringReader read len 0 at eof", () -> { StringReader r = new StringReader(""); return r.read(new char[1], 0, 0) + " " + r.read(new char[1], 0, 1); });
        t("StringReader closed", () -> { StringReader r = new StringReader("abc"); r.close(); return r.read(); });
        t("StringReader ready", () -> { StringReader r = new StringReader(""); return r.ready(); });
        t("CharArrayReader reset unmarked", () -> { CharArrayReader r = new CharArrayReader("abc".toCharArray(), 1, 2); r.read(); r.reset(); return (char) r.read(); });
        t("CharArrayReader skip -1", () -> new CharArrayReader("abc".toCharArray()).skip(-1));
        t("CharArrayReader read len 0 at eof", () -> { CharArrayReader r = new CharArrayReader(new char[0]); return r.read(new char[1], 0, 0); });
        t("bais reset unmarked", () -> { ByteArrayInputStream s = new ByteArrayInputStream(new byte[]{5, 6, 7}, 1, 2); s.read(); s.reset(); return s.read(); });
        t("StringWriter append", () -> new StringWriter().append("ab").append(null).append('c').append("xyz", 1, 2).toString());
        t("CharArrayWriter", () -> { CharArrayWriter w = new CharArrayWriter(); w.write("hey"); w.write(66); return w.size() + " " + w + " " + w.toCharArray().length; });
        System.out.println("done");
    }
}
