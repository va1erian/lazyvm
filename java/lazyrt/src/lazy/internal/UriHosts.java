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
     * It ends at `q` or a ':' (the port).
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
        // Observed: a char after the name is reported before the last label's first char.
        if (i < q && s.charAt(i) != ':') {
            throw fail(s, "Illegal character in hostname", i);
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
        int end = zone >= 0 && zone < close ? zone : close;
        new Ipv6(s, p + 1, end).address();
        if (end < close) {
            scope(s, zone, close);
        }
        return close + 1;
    }

    /** Observed: a zone (scope id) is one or more ASCII letters and digits, checked after the address. */
    private static void scope(String s, int zone, int close) throws URISyntaxException {
        if (zone + 1 == close) {
            throw fail(s, "scope id expected", -1);
        }
        for (int i = zone + 1; i < close; i++) {
            if (!alnum(s.charAt(i))) {
                throw fail(s, "Illegal character in scope id", i);
            }
        }
    }

    /**
     * An IPv6 address in `s[a, b)`: groups of 1 to 4 hex digits joined by ':',
     * at most one "::", and optionally an IPv4 address last. The rules below
     * reproduce the oracle's messages and indexes; they were derived from about
     * 170,000 probed literals (the "second part" is what follows a "::").
     */
    private static final class Ipv6 {
        private final String s;
        private final int a;
        private final int b;
        private int groups;

        Ipv6(String s, int a, int b) {
            this.s = s;
            this.a = a;
            this.b = b;
        }

        void address() throws URISyntaxException {
            int p = a;
            boolean compressed = false;
            if (!s.startsWith("::", p)) {
                p = sequence(p, false);
            }
            if (p < b && s.startsWith("::", p)) {
                compressed = true;
                p += 2;
                if (p < b) {
                    p = sequence(p, true);
                }
            }
            if (p < b) {
                throw fail(s, "Malformed IPv6 address", a);
            }
            if (groups > 8) {
                throw fail(s, "IPv6 address too long", a);
            }
            if (compressed && groups == 8) {
                throw fail(s, "Malformed IPv6 address", a);
            }
            if (!compressed && groups < 8) {
                throw fail(s, "IPv6 address too short", a);
            }
        }

        /** Groups joined by single ':' from `p`; in the first part it stops before a "::". */
        private int sequence(int p, boolean second) throws URISyntaxException {
            while (true) {
                p = group(p, second);
                if (p >= b || s.charAt(p) != ':' || (!second && s.startsWith("::", p))) {
                    return p;
                }
                p++;
                if (p >= b || !UriText.hex(s.charAt(p))) {
                    boolean colon = p < b && s.charAt(p) == ':';
                    throw fail(s, colon ? "Expected hex digits or IPv4 address" : "Expected digits for an IPv6 address", p);
                }
            }
        }

        /** One group, or the IPv4 address that ends the literal, at `p`. */
        private int group(int p, boolean second) throws URISyntaxException {
            int q = p;
            while (q < b && UriText.hex(s.charAt(q))) {
                q++;
            }
            if (q < b && s.charAt(q) == '.') {
                if (p == a) {
                    throw fail(s, "Malformed IPv6 address", a);
                }
                return ipv4(p, second);
            }
            if (q == p) {
                if (!second) {
                    throw fail(s, "Malformed IPv6 address", a);
                }
                return ipv4(p, true);
            }
            if (q - p > 4) {
                throw fail(s, "IPv6 hexadecimal digit sequence too long", p);
            }
            groups++;
            return q;
        }

        /**
         * Observed: an IPv4 address must run to the end in digits and dots, else the message
         * is about what was expected at `p`; then each of its four numbers is at most 255
         * (leading zeros allowed) and the first misplaced char is reported.
         */
        private int ipv4(int p, boolean second) throws URISyntaxException {
            int r = p;
            while (r < b && (UriParser.digit(s.charAt(r)) || s.charAt(r) == '.')) {
                r++;
            }
            if (r < b) {
                throw fail(s, second ? "Expected hex digits or IPv4 address" : "Expected IPv4 address", p);
            }
            int i = p;
            for (int part = 0; part < 4; part++) {
                if (part > 0) {
                    if (i >= b || s.charAt(i) != '.') {
                        throw fail(s, "Malformed IPv4 address", i);
                    }
                    i++;
                }
                int start = i;
                int value = 0;
                while (i < b && UriParser.digit(s.charAt(i))) {
                    value = Math.min(256, value * 10 + (s.charAt(i) - '0'));
                    i++;
                }
                if (i == start || value > 255) {
                    throw fail(s, "Malformed IPv4 address", start);
                }
            }
            if (i < b) {
                throw fail(s, "Malformed IPv4 address", i);
            }
            groups += 2;
            return i;
        }
    }
}
