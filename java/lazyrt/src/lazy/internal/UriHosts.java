package lazy.internal;

import java.net.URISyntaxException;

/**
 * The host of a server authority: an IPv4 address, a host name, or an IPv6
 * literal in brackets (RFC 2396 and RFC 2732, as the java.net.URI Javadoc
 * describes them). Each returns the index after the host or throws with the
 * oracle's message and index.
 */
final class UriHosts {
    private UriHosts() {
    }

    private static URISyntaxException fail(String s, String reason, int at) {
        return new URISyntaxException(s, reason, at);
    }

    private static boolean alnum(char c) {
        return UriParser.alpha(c) || UriParser.digit(c);
    }

    private static boolean hex(char c) {
        return Character.digit(c, 16) >= 0 && c < 0x80;
    }

    /** An IPv4 address at `p` ending at `q` or a ':', or -1. */
    private static int ipv4(String s, int p, int q) {
        int i = p;
        for (int part = 0; part < 4; part++) {
            if (part > 0) {
                if (i >= q || s.charAt(i) != '.') {
                    return -1;
                }
                i++;
            }
            int start = i;
            while (i < q && UriParser.digit(s.charAt(i)) && i - start < 3) {
                i++;
            }
            if (i == start || Integer.parseInt(s.substring(start, i)) > 255) {
                return -1;
            }
        }
        return i == q || s.charAt(i) == ':' ? i : -1;
    }

    /**
     * Observed: labels of letters, digits and inner '-', joined by '.', with an
     * optional final '.'; with more than one label the last starts with a letter.
     */
    static int hostname(String s, int p, int q) throws URISyntaxException {
        int v4 = ipv4(s, p, q);
        if (v4 >= 0) {
            return v4;
        }
        int i = p;
        int labels = 0;
        int last = p;
        while (true) {
            if (i >= q || !alnum(s.charAt(i))) {
                throw fail(s, "Illegal character in hostname", i);
            }
            last = i;
            labels++;
            while (i < q && (alnum(s.charAt(i)) || s.charAt(i) == '-')) {
                i++;
            }
            if (s.charAt(i - 1) == '-') {
                throw fail(s, "Illegal character in hostname", i - 1);
            }
            if (i < q && s.charAt(i) == '.') {
                i++;
                if (i < q && s.charAt(i) != ':') {
                    continue;
                }
            }
            break;
        }
        if (labels > 1 && !UriParser.alpha(s.charAt(last))) {
            throw fail(s, "Illegal character in hostname", last);
        }
        return i;
    }

    /** `[` at `p`: the literal up to `]`, with an optional zone after '%'. */
    static int ipv6(String s, int p, int q) throws URISyntaxException {
        int close = s.indexOf(']', p + 1);
        if (close < 0 || close >= q) {
            throw fail(s, "Expected closing bracket for IPv6 address", q);
        }
        if (close == p + 1) {
            throw fail(s, "Expected closing bracket for IPv6 address", close);
        }
        int zone = s.indexOf('%', p + 1);
        address(s, p + 1, zone >= 0 && zone < close ? zone : close);
        return close + 1;
    }

    private static void address(String s, int a, int b) throws URISyntaxException {
        char first = s.charAt(a);
        if (!hex(first) && first != ':') {
            throw fail(s, "Malformed IPv6 address", a);
        }
        int groups = 0;
        boolean compressed = false;
        int i = a;
        if (s.startsWith("::", i)) {
            compressed = true;
            i += 2;
        } else if (first == ':') {
            throw fail(s, "Malformed IPv6 address", a);
        }
        while (i < b) {
            int j = i;
            while (j < b && hex(s.charAt(j)) && j - i < 4) {
                j++;
            }
            if (j < b && s.charAt(j) == '.') {
                if (ipv4(s, i, b) != b) {
                    throw fail(s, "Malformed IPv4 address", i);
                }
                groups += 2;
                break;
            }
            if (j == i) {
                throw fail(s, "Expected hex digits or IPv4 address", i);
            }
            groups++;
            i = j;
            if (i == b) {
                break;
            }
            if (s.charAt(i) != ':') {
                throw fail(s, "Malformed IPv6 address", a);
            }
            if (i + 1 < b && s.charAt(i + 1) == ':') {
                if (compressed) {
                    throw fail(s, "Malformed IPv6 address", a);
                }
                compressed = true;
                i += 2;
            } else {
                i++;
                if (i == b) {
                    throw fail(s, "Expected hex digits or IPv4 address", i);
                }
            }
        }
        if (groups > 8 || (compressed && groups > 7)) {
            throw fail(s, "IPv6 address too long", a);
        }
        if (!compressed && groups < 8) {
            throw fail(s, "IPv6 address too short", a);
        }
    }
}
