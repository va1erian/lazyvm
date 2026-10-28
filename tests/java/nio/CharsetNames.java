package nio;

import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.Callable;

/** Charset lookup by name and alias for the six standard charsets, name validation, and the
 *  Charset object's own methods. Only the standard charsets are used: LazyVM need not have others. */
public class CharsetNames {
    static void t(String label, Callable<Object> c) {
        String r;
        try {
            r = String.valueOf(c.call());
        } catch (Throwable e) {
            r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        System.out.println(label + ": " + r);
    }

    public static void main(String[] args) throws Exception {
        String[] std = {"US-ASCII", "ISO-8859-1", "UTF-8", "UTF-16", "UTF-16BE", "UTF-16LE"};
        for (String n : std) {
            Charset cs = Charset.forName(n);
            System.out.println(n + ": name=" + cs.name() + " display=" + cs.displayName() + " toString=" + cs
                + " registered=" + cs.isRegistered() + " canEncode=" + cs.canEncode()
                + " aliases=" + new TreeSet<>(cs.aliases()));
        }
        String[] lookups = {"utf-8", "Utf-8", "UTF8", "utf8", "unicode-1-1-utf-8", "ASCII", "ascii", "us-ascii", "646", "iso_646.irv:1983",
            "ANSI_X3.4-1968", "cp367", "csASCII", "default", "ISO8859_1", "iso8859_1", "latin1", "l1", "8859_1", "ISO_8859-1", "cp819",
            "IBM819", "819", "csISOLatin1", "UTF_16", "utf16", "UnicodeBig", "UTF_16BE", "X-UTF-16BE", "UnicodeBigUnmarked", "ISO-10646-UCS-2",
            "UTF_16LE", "X-UTF-16LE", "UnicodeLittleUnmarked", "unicode"};
        for (String n : lookups) {
            t("forName " + n, () -> Charset.forName(n).name());
        }
        String[] bad = {null, "", "-utf8", "utf 8", "utf8!", "_x", ".x", ":x", "+x", "x+", "a.b:c-d_e+", "no-such-charset", "x-no-such", "été"};
        for (String n : bad) {
            t("forName [" + n + "]", () -> Charset.forName(n));
            t("isSupported [" + n + "]", () -> Charset.isSupported(n));
        }
        t("StandardCharsets identity", () -> (StandardCharsets.UTF_8 == Charset.forName("utf8")) + " " + StandardCharsets.UTF_8.equals(Charset.forName("UTF-8")));
        t("hashCode is name hash", () -> Charset.forName("utf8").hashCode() == "UTF-8".hashCode());
        t("compareTo", () -> Integer.signum(StandardCharsets.UTF_8.compareTo(StandardCharsets.US_ASCII)) + " " + Integer.signum(StandardCharsets.UTF_16.compareTo(StandardCharsets.UTF_16BE)));
        t("contains", () -> StandardCharsets.UTF_8.contains(StandardCharsets.US_ASCII) + " " + StandardCharsets.UTF_8.contains(StandardCharsets.UTF_16)
            + " " + StandardCharsets.US_ASCII.contains(StandardCharsets.ISO_8859_1) + " " + StandardCharsets.ISO_8859_1.contains(StandardCharsets.US_ASCII)
            + " " + StandardCharsets.UTF_16LE.contains(StandardCharsets.UTF_8) + " " + StandardCharsets.US_ASCII.contains(StandardCharsets.US_ASCII));
        t("default", () -> Charset.defaultCharset().name());
        t("available has standard", () -> {
            SortedMap<String, Charset> m = Charset.availableCharsets();
            StringBuilder sb = new StringBuilder();
            for (String n : std) sb.append(m.containsKey(n)).append(m.containsKey(n.toLowerCase())).append(' ');
            return sb;
        });
        t("encode via Charset", () -> Arrays.toString(StandardCharsets.US_ASCII.encode("aé").array()).replace(", 0", ""));
        t("encode limit", () -> StandardCharsets.UTF_8.encode("€").limit());
        t("decode", () -> StandardCharsets.ISO_8859_1.decode(java.nio.ByteBuffer.wrap(new byte[]{(byte) 0xe9})).toString().equals("é"));
        t("IllegalCharsetNameException", () -> new IllegalCharsetNameException("q").getCharsetName() + " " + new IllegalCharsetNameException("q").getMessage());
        t("UnsupportedCharsetException", () -> new UnsupportedCharsetException("q").getCharsetName() + " " + new UnsupportedCharsetException("q").getMessage());
        System.out.println("done");
    }
}
