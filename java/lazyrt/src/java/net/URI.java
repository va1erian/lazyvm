package java.net;

import lazy.internal.UriText;

/**
 * The part of URI that file names need before java.net (S7-02): parsing
 * into scheme, authority, path, query and fragment (RFC 2396's shape, with
 * the component checks and messages of the Javadoc), the component getters,
 * comparison, and the (scheme, host, path, fragment) constructor.
 * Server-based authority parsing (user info, port), normalize, resolve and
 * relativize come with S7-02. Escaping is lazy.internal.UriText's.
 */
public final class URI implements Comparable<URI>, java.io.Serializable {
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

    private static String build(String scheme, String host, String path, String fragment)
            throws URISyntaxException {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) {
            sb.append(scheme).append(':');
        }
        if (host != null) {
            sb.append("//").append(host);
        }
        if (path != null) {
            sb.append(UriText.escape(path, UriText.PATH, true));
        }
        if (fragment != null) {
            sb.append('#').append(UriText.escape(fragment, UriText.QUERY, true));
        }
        String s = sb.toString();
        if (scheme != null && path != null && !path.isEmpty() && path.charAt(0) != '/') {
            throw new URISyntaxException(s, "Relative path in absolute URI");
        }
        return s;
    }

    private URISyntaxException fail(String what, int at) {
        return new URISyntaxException(string, what, at);
    }

    /** Checks `string[from, to)` as the named component may hold it. */
    private void check(int from, int to, String legal, String component) throws URISyntaxException {
        int bad = UriText.illegal(string, from, to, legal);
        if (bad >= 0) {
            // Observed: an authority is reported at its start, other components at the char.
            throw fail("Illegal character in " + component, component.equals("authority") ? from : bad);
        }
        bad = UriText.badEscape(string, from, to);
        if (bad >= 0) {
            throw fail("Malformed escape pair", bad);
        }
    }

    private void parse() throws URISyntaxException {
        String s = string;
        int end = s.length();
        int hash = s.indexOf('#');
        if (hash >= 0) {
            check(hash + 1, end, UriText.QUERY, "fragment");
            fragment = s.substring(hash + 1);
            end = hash;
        }
        int colon = s.indexOf(':');
        int stop = firstOf(s, "/?#");
        int start = 0;
        if (colon == 0) {
            throw fail("Expected scheme name", 0);
        }
        if (colon > 0 && (stop < 0 || colon < stop)) {
            checkScheme(colon);
            scheme = s.substring(0, colon);
            start = colon + 1;
            if (start == end) {
                throw fail("Expected scheme-specific part", start);
            }
            if (s.charAt(start) != '/') {
                check(start, end, UriText.QUERY, "opaque part");
                opaque = true;
                return;
            }
        }
        int q = s.indexOf('?', start);
        if (q >= 0 && q < end) {
            check(q + 1, end, UriText.QUERY, "query");
            query = s.substring(q + 1, end);
            end = q;
        }
        if (s.startsWith("//", start)) {
            int slash = s.indexOf('/', start + 2);
            int authEnd = slash < 0 || slash > end ? end : slash;
            check(start + 2, authEnd, UriText.AUTHORITY, "authority");
            authority = authEnd > start + 2 ? s.substring(start + 2, authEnd) : null;
            start = authEnd;
        }
        check(start, end, UriText.PATH, "path");
        path = s.substring(start, end);
    }

    private void checkScheme(int colon) throws URISyntaxException {
        for (int i = 0; i < colon; i++) {
            char c = string.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!letter && (i == 0 || !((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'))) {
                throw fail("Illegal character in scheme name", i);
            }
        }
    }

    private static int firstOf(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
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
        return UriText.decode(authority);
    }

    /** The authority as a host name; user info and ports come with S7-02. */
    public String getHost() {
        return authority;
    }

    public String getRawPath() {
        return path;
    }

    public String getPath() {
        return UriText.decode(path);
    }

    public String getRawQuery() {
        return query;
    }

    public String getQuery() {
        return UriText.decode(query);
    }

    public String getRawFragment() {
        return fragment;
    }

    public String getFragment() {
        return UriText.decode(fragment);
    }

    public String toString() {
        return string;
    }

    public String toASCIIString() {
        return UriText.escape(string, UriText.ASCII + "%", false);
    }

    /**
     * Javadoc of equals: the scheme and host ignore case, and so do the hex digits of escapes.
     * Observed: compareTo ignores case in the scheme and host only.
     */
    private String comparable(boolean escapes) {
        String rest = scheme == null ? string : string.substring(scheme.length());
        String lower = scheme == null ? "" : scheme.toLowerCase();
        if (authority != null) {
            int at = rest.indexOf(authority);
            rest = rest.substring(0, at) + authority.toLowerCase() + rest.substring(at + authority.length());
        }
        return lower + (escapes ? UriText.normalEscapes(rest) : rest);
    }

    public boolean equals(Object ob) {
        return ob instanceof URI && comparable(true).equals(((URI) ob).comparable(true));
    }

    public int hashCode() {
        return comparable(true).hashCode();
    }

    public int compareTo(URI that) {
        return comparable(false).compareTo(that.comparable(false));
    }
}
