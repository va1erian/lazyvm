package java.net;

import java.util.Locale;
import lazy.internal.UriParser;
import lazy.internal.UriText;

/**
 * A URI reference: parsing into scheme, scheme-specific part, authority
 * (user info, host and port when it is a server authority), path, query and
 * fragment (lazy.internal.UriParser), the component getters, comparison in
 * the Javadoc's component order, and the (scheme, host, path, fragment)
 * constructor. normalize, resolve, relativize and toURL come with S7-02.
 * Escaping is lazy.internal.UriText's.
 */
public final class URI implements Comparable<URI>, java.io.Serializable {
    private final String string;
    private final String scheme;
    private final String ssp;
    private final String authority;
    private final String userInfo;
    private final String host;
    private final int port;
    private final String path;
    private final String query;
    private final String fragment;

    public URI(String str) throws URISyntaxException {
        this(str, false);
    }

    private URI(String str, boolean server) throws URISyntaxException {
        this(str, UriParser.parse(str, server));
    }

    private URI(String str, UriParser p) {
        string = str;
        scheme = p.scheme;
        ssp = p.ssp;
        authority = p.authority;
        userInfo = p.userInfo;
        host = p.host;
        port = p.port;
        path = p.path;
        query = p.query;
        fragment = p.fragment;
    }

    // Javadoc: built from the components, quoted, then parsed with a server authority required.
    public URI(String scheme, String host, String path, String fragment) throws URISyntaxException {
        this(build(scheme, host, path, fragment), true);
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
            boolean literal = host.indexOf(':') >= 0 && !host.startsWith("[");
            sb.append("//").append(literal ? "[" + host + "]" : host);
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

    public URI parseServerAuthority() throws URISyntaxException {
        if (authority == null || host != null) {
            return this;
        }
        return new URI(string, true);
    }

    public String getScheme() {
        return scheme;
    }

    public boolean isAbsolute() {
        return scheme != null;
    }

    public boolean isOpaque() {
        return path == null;
    }

    public String getRawSchemeSpecificPart() {
        return ssp;
    }

    public String getSchemeSpecificPart() {
        return UriText.decode(ssp, true);
    }

    public String getRawAuthority() {
        return authority;
    }

    public String getAuthority() {
        return UriText.decode(authority, true);
    }

    public String getRawUserInfo() {
        return userInfo;
    }

    public String getUserInfo() {
        return UriText.decode(userInfo);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
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
        return UriText.decode(query, true);
    }

    public String getRawFragment() {
        return fragment;
    }

    public String getFragment() {
        return UriText.decode(fragment, true);
    }

    public String toString() {
        return string;
    }

    public String toASCIIString() {
        return UriText.escape(string, UriText.ASCII + "%", false);
    }

    /** Javadoc of equals: raw forms, escapes' hex digits ignoring case. */
    private static boolean same(String a, String b) {
        return a == null ? b == null : b != null && UriText.normalEscapes(a).equals(UriText.normalEscapes(b));
    }

    private static boolean sameIgnoringCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    // Javadoc: scheme and host ignore case; a server authority compares user info, host, port.
    public boolean equals(Object ob) {
        if (!(ob instanceof URI)) {
            return false;
        }
        URI that = (URI) ob;
        if (isOpaque() != that.isOpaque() || !sameIgnoringCase(scheme, that.scheme) || !same(fragment, that.fragment)) {
            return false;
        }
        if (isOpaque()) {
            return same(ssp, that.ssp);
        }
        if (!same(path, that.path) || !same(query, that.query)) {
            return false;
        }
        if (host != null && that.host != null) {
            return same(userInfo, that.userInfo) && host.equalsIgnoreCase(that.host) && port == that.port;
        }
        return same(authority, that.authority);
    }

    // Values match the oracle's, found by probing: components fold in 127-wise (escapes with
    // upper-case hex), scheme and host fold per lower-cased character 31-wise, port adds 1949 each.
    private static int hash(int h, String s) {
        return s == null ? h : h * 127 + UriText.normalEscapes(s).hashCode();
    }

    private static int hashIgnoringCase(int h, String s) {
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + Character.toLowerCase(s.charAt(i));
        }
        return h;
    }

    public int hashCode() {
        int h = scheme == null ? 0 : hashIgnoringCase(0, scheme);
        h = hash(h, fragment);
        if (isOpaque()) {
            return hash(h, ssp);
        }
        h = hash(hash(h, path), query);
        if (host != null) {
            h = hash(h, userInfo);
            return hashIgnoringCase(h, host) + 1949 * port;
        }
        return hash(h, authority);
    }

    /** Undefined sorts first; strings in their natural, case-sensitive order. */
    private static int order(String a, String b, boolean ignoreCase) {
        if (a == null || b == null) {
            return a == b ? 0 : a == null ? -1 : 1;
        }
        return ignoreCase ? a.compareToIgnoreCase(b) : a.compareTo(b);
    }

    // Javadoc of compareTo: scheme, then opaque after hierarchical, then component by component.
    public int compareTo(URI that) {
        int c = order(scheme, that.scheme, true);
        if (c != 0) {
            return c;
        }
        if (isOpaque() || that.isOpaque()) {
            if (isOpaque() != that.isOpaque()) {
                return isOpaque() ? 1 : -1;
            }
            c = order(ssp, that.ssp, false);
            return c != 0 ? c : order(fragment, that.fragment, false);
        }
        if (host != null && that.host != null) {
            c = order(userInfo, that.userInfo, false);
            if (c == 0) {
                c = order(host, that.host, true);
            }
            if (c == 0) {
                c = port - that.port;
            }
        } else {
            c = order(authority, that.authority, false);
        }
        if (c == 0) {
            c = order(path, that.path, false);
        }
        if (c == 0) {
            c = order(query, that.query, false);
        }
        return c != 0 ? c : order(fragment, that.fragment, false);
    }
}
