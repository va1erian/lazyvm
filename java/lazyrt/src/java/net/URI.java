package java.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The part of URI that file names need before java.net (S7-02): parsing
 * into scheme, authority, path, query and fragment (RFC 2396's shape), the
 * component getters, and the (scheme, host, path, fragment) constructor.
 * Server-based authority parsing (user info, port), normalize, resolve and
 * relativize come with S7-02.
 */
public final class URI implements Comparable<URI>, java.io.Serializable {
    private static final String PUNCT = "-_.!~*'();/?:@&=+$,[]%";

    private final String string;
    private String scheme;
    private String authority;
    private String path;
    private String query;
    private String fragment;
    private boolean opaque;

    public URI(String str) throws URISyntaxException {
        this.string = str;
        parse();
    }

    public URI(String scheme, String host, String path, String fragment) throws URISyntaxException {
        this(build(scheme, host, path, fragment));
    }

    public static URI create(String str) {
        try {
            return new URI(str);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static String build(String scheme, String host, String path, String fragment) {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) {
            sb.append(scheme).append(':');
        }
        if (host != null) {
            sb.append("//").append(host);
        }
        if (path != null) {
            sb.append(quote(path));
        }
        if (fragment != null) {
            sb.append('#').append(quote(fragment));
        }
        return sb.toString();
    }

    /** Escapes what is not legal in a component; non-ASCII "other" chars stay (Javadoc). */
    private static String quote(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (legal(c) && c != '%' && c != '?' && c != '#') {
                sb.append(c);
            } else {
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    sb.append('%').append("0123456789ABCDEF".charAt((b >> 4) & 15))
                            .append("0123456789ABCDEF".charAt(b & 15));
                }
            }
        }
        return sb.toString();
    }

    private static boolean legal(char c) {
        if (c > 0x7F) {
            return !Character.isISOControl(c) && !Character.isSpaceChar(c);
        }
        return Character.isLetterOrDigit(c) || PUNCT.indexOf(c) >= 0;
    }

    private void parse() throws URISyntaxException {
        String s = string;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!legal(c) && c != '#') {
                throw new URISyntaxException(s, "Illegal character in URI", i);
            }
        }
        int hash = s.indexOf('#');
        if (hash >= 0) {
            fragment = s.substring(hash + 1);
            s = s.substring(0, hash);
        }
        int colon = s.indexOf(':');
        int stop = firstOf(s, "/?");
        if (colon > 0 && (stop < 0 || colon < stop) && validScheme(s.substring(0, colon))) {
            scheme = s.substring(0, colon);
            s = s.substring(colon + 1);
            if (s.isEmpty()) {
                throw new URISyntaxException(string, "Expected scheme-specific part", string.length());
            }
            if (!s.startsWith("/")) {
                opaque = true;
                return;
            }
        }
        int q = s.indexOf('?');
        if (q >= 0) {
            query = s.substring(q + 1);
            s = s.substring(0, q);
        }
        if (s.startsWith("//")) {
            int end = s.indexOf('/', 2);
            end = end < 0 ? s.length() : end;
            authority = end > 2 ? s.substring(2, end) : null;
            s = s.substring(end);
        }
        path = s;
    }

    private static int firstOf(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean validScheme(String s) {
        if (!Character.isLetter(s.charAt(0)) || s.charAt(0) > 0x7F) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7F || !(Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    /** %XX escapes as UTF-8 bytes. */
    private static String decode(String s) {
        if (s == null || s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && Character.digit(s.charAt(i + 1), 16) >= 0
                    && Character.digit(s.charAt(i + 2), 16) >= 0) {
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

    private static void flush(ByteArrayOutputStream bytes, StringBuilder sb) {
        if (bytes.size() > 0) {
            sb.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            bytes.reset();
        }
    }

    public String getScheme() {
        return scheme;
    }

    public boolean isAbsolute() {
        return scheme != null;
    }

    public boolean isOpaque() {
        return opaque;
    }

    public String getRawAuthority() {
        return authority;
    }

    public String getAuthority() {
        return decode(authority);
    }

    /** The authority as a host name; user info and ports come with S7-02. */
    public String getHost() {
        return authority;
    }

    public String getRawPath() {
        return path;
    }

    public String getPath() {
        return decode(path);
    }

    public String getRawQuery() {
        return query;
    }

    public String getQuery() {
        return decode(query);
    }

    public String getRawFragment() {
        return fragment;
    }

    public String getFragment() {
        return decode(fragment);
    }

    public String toString() {
        return string;
    }

    public String toASCIIString() {
        StringBuilder sb = new StringBuilder();
        for (byte b : string.getBytes(StandardCharsets.UTF_8)) {
            if (b >= 0) {
                sb.append((char) b);
            } else {
                sb.append('%').append("0123456789ABCDEF".charAt((b >> 4) & 15))
                        .append("0123456789ABCDEF".charAt(b & 15));
            }
        }
        return sb.toString();
    }

    public boolean equals(Object ob) {
        return ob instanceof URI && string.equals(((URI) ob).string);
    }

    public int hashCode() {
        return string.hashCode();
    }

    public int compareTo(URI that) {
        return string.compareTo(that.string);
    }
}
