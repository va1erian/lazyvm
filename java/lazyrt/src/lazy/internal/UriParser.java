package lazy.internal;

import java.net.URISyntaxException;

/**
 * Splits a URI string into its components (RFC 2396 with IPv6 literals, as
 * the java.net.URI Javadoc describes it), left to right, so the leftmost bad
 * component is the one reported. Messages and indexes follow the oracle's
 * (tests/java/nio/UriText). An authority that is not a valid server
 * authority (user info, host, port) is registry-based: no host, port -1.
 */
public final class UriParser {
    /** Authority chars a server authority may hold (user info, host, port, IPv6). */
    private static final String SERVER = UriText.UNRESERVED + ";:&=+$,@[]";
    private static final String REGISTRY = UriText.UNRESERVED + "$,;:@&=+";
    private static final String USER_INFO = UriText.UNRESERVED + ";:&=+$,";

    private final String s;
    public String scheme;
    public String ssp;
    public String authority;
    public String userInfo;
    public String host;
    public int port = -1;
    public String path;
    public String query;
    public String fragment;

    private UriParser(String s) {
        this.s = s;
    }

    /** Parses `s`; with `server` the authority must be a server authority. */
    public static UriParser parse(String s, boolean server) throws URISyntaxException {
        UriParser p = new UriParser(s);
        p.parse(server);
        return p;
    }

    private URISyntaxException fail(String reason, int at) {
        return new URISyntaxException(s, reason, at);
    }

    private int firstOf(String chars, int from) {
        for (int i = from; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return s.length();
    }

    /** Checks `s[from, to)` as a component; the reason names it. */
    private void check(int from, int to, String legal, String component) throws URISyntaxException {
        int bad = UriText.scan(s, from, to, legal);
        if (bad < to) {
            throw fail("Illegal character in " + component, bad);
        }
    }

    private void parse(boolean server) throws URISyntaxException {
        int n = s.length();
        int p = 0;
        int c = firstOf(":/?#", 0);
        if (c < n && s.charAt(c) == ':') {
            if (c == 0) {
                throw fail("Expected scheme name", 0);
            }
            checkScheme(c);
            scheme = s.substring(0, c);
            p = c + 1;
            if (p == n || s.charAt(p) == '#') {
                throw fail("Expected scheme-specific part", p);
            }
        }
        int hash = s.indexOf('#', p);
        int end = hash < 0 ? n : hash;
        ssp = s.substring(p, end);
        if (scheme != null && s.charAt(p) != '/') {
            check(p, end, UriText.QUERY, "opaque part");
        } else {
            hierarchical(p, end, server);
        }
        if (hash >= 0) {
            check(hash + 1, n, UriText.QUERY, "fragment");
            fragment = s.substring(hash + 1);
        }
    }

    private void checkScheme(int colon) throws URISyntaxException {
        for (int i = 0; i < colon; i++) {
            char c = s.charAt(i);
            boolean ok = alpha(c) || (i > 0 && (digit(c) || c == '+' || c == '-' || c == '.'));
            if (!ok) {
                throw fail("Illegal character in scheme name", i);
            }
        }
    }

    private void hierarchical(int p, int end, boolean server) throws URISyntaxException {
        if (s.startsWith("//", p)) {
            p += 2;
            if (p == s.length()) {
                throw fail("Expected authority", p);
            }
            int q = Math.min(firstOf("/?#", p), end);
            if (q > p) {
                authority(p, q, server);
            }
            p = q;
        }
        int q = Math.min(firstOf("?#", p), end);
        check(p, q, UriText.PATH, "path");
        path = s.substring(p, q);
        if (q < end) {
            check(q + 1, end, UriText.QUERY, "query");
            query = s.substring(q + 1, end);
        }
    }

    // Observed: chars legal for neither form fail at the authority's start; a failed server
    // authority whose chars a registry name allows is a registry name. Escapes are judged only
    // when it comes to the registry name (or the user info).
    private void authority(int p, int q, boolean server) throws URISyntaxException {
        authority = s.substring(p, q);
        if (!serverChars(p, q)) {
            if (UriText.scan(s, p, q, REGISTRY) < q) {
                throw fail("Illegal character in authority", p);
            }
            if (!server) {
                return;
            }
        }
        try {
            server(p, q);
        } catch (URISyntaxException e) {
            userInfo = null;
            host = null;
            port = -1;
            if (server || UriText.scan(s, p, q, REGISTRY) < q) {
                throw e;
            }
        }
    }

    private boolean serverChars(int p, int q) {
        for (int i = p; i < q; i++) {
            char c = s.charAt(i);
            if (c != '%' && !UriText.legal(c, SERVER)) {
                return false;
            }
        }
        return true;
    }

    private void server(int p, int q) throws URISyntaxException {
        int at = s.indexOf('@', p);
        if (at >= 0 && at < q) {
            check(p, at, USER_INFO, "user info");
            userInfo = s.substring(p, at);
            p = at + 1;
        }
        if (p == q || s.charAt(p) == ':') {
            throw fail("Expected hostname", p);
        }
        int h = p;
        if (s.charAt(p) == '[') {
            p = UriHosts.ipv6(s, p, q);
            if (p < q && s.charAt(p) != ':') {
                throw fail("Expected port number", p);
            }
        } else {
            p = UriHosts.hostname(s, p, q);
            if (p < q && s.charAt(p) != ':') {
                throw fail("Illegal character in hostname", p);
            }
        }
        host = s.substring(h, p);
        if (p < q) {
            port(p + 1, q);
        }
    }

    private void port(int p, int q) throws URISyntaxException {
        for (int i = p; i < q; i++) {
            if (!digit(s.charAt(i))) {
                throw fail("Illegal character in port number", i);
            }
        }
        if (q > p) {
            try {
                port = Integer.parseInt(s.substring(p, q));
            } catch (NumberFormatException e) {
                throw fail("Malformed port number", p);
            }
        }
    }

    static boolean alpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }
}
