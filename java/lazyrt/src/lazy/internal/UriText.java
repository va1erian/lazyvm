package lazy.internal;

import java.nio.charset.StandardCharsets;

/**
 * The one place URI text is escaped and checked, for java.net.URI, File.toURI
 * and Path.toUri: which chars a component may hold (RFC 2396 as the URI
 * Javadoc describes it) and percent-escapes of UTF-8 bytes for the rest.
 * Non-ASCII chars that are neither controls nor spaces ("other") may stay.
 */
public final class UriText {
    /** Letters and digits are always legal; these marks too. */
    public static final String UNRESERVED = "-_.!~*'()";
    /** What a path may hold besides letters, digits and escapes. */
    public static final String PATH = UNRESERVED + ";:@&=+$,/";
    /** A query, a fragment or an opaque part. */
    public static final String QUERY = PATH + "?[]";
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

    /** Whether `c` is a letter, a digit, in `legal` or an "other" char. */
    public static boolean legal(char c, String legal) {
        return c < 0x80 ? ascii(c, legal) : other(c);
    }

    /**
     * Scans `s[from, to)` while its chars are legal (letters, digits, `legal`, "other" chars
     * and escapes): the index of the first that is not, or `to`. A '%' not followed by two
     * hex digits is a "Malformed escape pair" where it stands.
     */
    public static int scan(String s, int from, int to, String legal) throws java.net.URISyntaxException {
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (c == '%') {
                if (!(i + 2 < to && hex(s.charAt(i + 1)) && hex(s.charAt(i + 2)))) {
                    throw new java.net.URISyntaxException(s, "Malformed escape pair", i);
                }
                i += 3;
            } else if (legal(c, legal)) {
                i++;
            } else {
                return i;
            }
        }
        return to;
    }

    private static boolean hex(char c) {
        return Character.digit(c, 16) >= 0 && c < 0x80;
    }

    /** %XX escapes decoded as UTF-8; malformed bytes become U+FFFD. */
    public static String decode(String s) {
        return decode(s, false);
    }

    /** With `literals`, escapes inside `[...]` (an IPv6 zone) stay as they are (observed). */
    public static String decode(String s, boolean literals) {
        if (s == null || s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        boolean inside = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (literals && (c == '[' || c == ']')) {
                inside = c == '[';
            }
            if (c == '%' && !inside && i + 2 < s.length() && hex(s.charAt(i + 1)) && hex(s.charAt(i + 2))) {
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
