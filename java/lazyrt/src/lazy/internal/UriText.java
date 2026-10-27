package lazy.internal;

import java.nio.charset.StandardCharsets;

/**
 * The one place URI text is escaped and checked, for java.net.URI, File.toURI
 * and Path.toUri: which chars a component may hold (RFC 2396 as the URI
 * Javadoc describes it) and percent-escapes of UTF-8 bytes for the rest.
 * Non-ASCII chars that are neither controls nor spaces ("other") may stay.
 */
public final class UriText {
    private static final String UNRESERVED = "-_.!~*'()";
    /** What a path may hold besides letters, digits and escapes. */
    public static final String PATH = UNRESERVED + ";:@&=+$,/";
    /** A query, a fragment or an opaque part. */
    public static final String QUERY = PATH + "?[]";
    /** A server-based or registry authority. */
    public static final String AUTHORITY = UNRESERVED + ";:@&=+$,[]";
    /** Everything printable in ASCII except '%', for toASCIIString. */
    public static final String ASCII;

    static {
        StringBuilder sb = new StringBuilder();
        for (char c = '!'; c < 0x7F; c++) {
            if (c != '%') {
                sb.append(c);
            }
        }
        ASCII = sb.toString();
    }

    private UriText() {
    }

    private static boolean ascii(char c, String legal) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || legal.indexOf(c) >= 0;
    }

    /** Javadoc of URI: "other" chars are non-ASCII, not control and not space chars. */
    private static boolean other(char c) {
        return c > 0x7F && !Character.isISOControl(c) && !Character.isSpaceChar(c);
    }

    /**
     * `s` with every char outside `legal` (and, unless `keepOther`, every
     * non-ASCII char) as %XX escapes of its UTF-8 bytes; existing escapes
     * stay only when `legal` holds '%'.
     */
    public static String escape(String s, String legal, boolean keepOther) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80 ? ascii(c, legal) : keepOther && other(c)) {
                sb.append(c);
                continue;
            }
            int end = Character.isHighSurrogate(c) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1)) ? i + 2 : i + 1;
            for (byte b : s.substring(i, end).getBytes(StandardCharsets.UTF_8)) {
                sb.append('%').append("0123456789ABCDEF".charAt((b >> 4) & 15))
                        .append("0123456789ABCDEF".charAt(b & 15));
            }
            i = end - 1;
        }
        return sb.toString();
    }

    /** The index in `s[from, to)` of the first char a component cannot hold, or -1. */
    public static int illegal(String s, int from, int to, String legal) {
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '%' || (c < 0x80 ? ascii(c, legal) : other(c))) {
                continue;
            }
            return i;
        }
        return -1;
    }

    /** The index in `s[from, to)` of a '%' not followed by two hex digits, or -1. */
    public static int badEscape(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == '%' && !(i + 2 < to && hex(s.charAt(i + 1)) && hex(s.charAt(i + 2)))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hex(char c) {
        return Character.digit(c, 16) >= 0 && c < 0x80;
    }

    /** %XX escapes decoded as UTF-8; malformed bytes become U+FFFD. */
    public static String decode(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) && hex(s.charAt(i + 2))) {
                bytes.write(Character.digit(s.charAt(i + 1), 16) * 16 + Character.digit(s.charAt(i + 2), 16));
                i += 2;
                continue;
            }
            flush(bytes, sb);
            sb.append(c);
        }
        flush(bytes, sb);
        return sb.toString();
    }

    private static void flush(java.io.ByteArrayOutputStream bytes, StringBuilder sb) {
        if (bytes.size() > 0) {
            sb.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            bytes.reset();
        }
    }

    /** The form URIs compare by: escapes' hex digits in upper case (Javadoc of equals). */
    public static String normalEscapes(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        for (int i = sb.indexOf("%"); i >= 0 && i + 2 < sb.length(); i = sb.indexOf("%", i + 1)) {
            sb.setCharAt(i + 1, Character.toUpperCase(sb.charAt(i + 1)));
            sb.setCharAt(i + 2, Character.toUpperCase(sb.charAt(i + 2)));
        }
        return sb.toString();
    }
}
