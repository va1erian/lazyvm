package nio;

import java.net.URI;
import java.net.URISyntaxException;

/** The part of java.net.URI that file names use: parsing errors with their messages and
 *  indexes, the component getters, the escaping constructor, and comparison, which ignores
 *  case in the scheme, the host and the hex digits of escapes. */
public class UriText {
    static void parse(String s) {
        String r;
        try {
            URI u = new URI(s);
            r = "scheme=" + u.getScheme() + " auth=" + u.getRawAuthority() + " path=" + u.getRawPath() + " query=" + u.getRawQuery()
                    + " frag=" + u.getRawFragment() + " opaque=" + u.isOpaque() + " decoded=" + u.getPath();
        } catch (URISyntaxException e) {
            r = "! " + e.getReason() + " at " + e.getIndex();
        }
        System.out.println(s + " -> " + r);
    }

    static void build(String scheme, String host, String path, String fragment) {
        String r;
        try {
            r = new URI(scheme, host, path, fragment).toString();
        } catch (URISyntaxException e) {
            r = "! " + e.getReason() + " at " + e.getIndex();
        }
        System.out.println("build " + path + " #" + fragment + " -> " + r);
    }

    static void authority(String a) {
        String r;
        try {
            URI u = new URI("http://" + a + "/p");
            r = "auth=" + u.getRawAuthority() + " decoded=" + u.getAuthority() + " user=" + u.getRawUserInfo()
                    + " host=" + u.getHost() + " port=" + u.getPort();
            try {
                u.parseServerAuthority();
            } catch (URISyntaxException e) {
                r += " (server: " + e.getReason() + " at " + e.getIndex() + ")";
            }
        } catch (URISyntaxException e) {
            r = "! " + e.getReason() + " at " + e.getIndex();
        }
        System.out.println("authority " + a + " -> " + r);
    }

    static String make(String scheme, String host, String path, String fragment) {
        try {
            URI u = new URI(scheme, host, path, fragment);
            return u + " host=" + u.getHost();
        } catch (URISyntaxException e) {
            return "! " + e.getReason() + " at " + e.getIndex();
        }
    }

    static void same(String a, String b) throws URISyntaxException {
        URI x = new URI(a), y = new URI(b);
        System.out.println(a + " vs " + b + ": equals=" + x.equals(y) + " hash=" + (x.hashCode() == y.hashCode()) + " compare=" + Integer.signum(x.compareTo(y)));
    }

    public static void main(String[] args) throws Exception {
        for (String s : new String[] {"file:///C:/x%20y/z", "file:/a/b", "file://host/share/x", "file:////host/share", "a/b?q#f",
                "%zz", "a%2", "a#b#c", ":x", "file:", "1a:b", "mailto:x@y", "file://h rel", "a b", "a?b c", "file:/a/%C3%A9",
                "file:/a\u00e9", "file:/a\u00a0b", "x:#f", "a[b]", "a?[b]", "//h/p", "", "#", "?q"}) {
            parse(s);
        }
        build("file", null, "/a b/c[d]", null);
        build("file", null, "/a\u00a0b\u2028c\u00e9", null);
        build("file", null, "rel", null);
        build("file", null, "/x", "f g");
        build(null, null, "a:b", null);
        same("FILE:/a", "file:/a");
        same("file://HOST/a", "file://host/a");
        same("file:/a%c3%a9", "file:/a%C3%A9");
        same("file:/A", "file:/a");
        same("file:/a#X", "file:/a#x");
        System.out.println("toASCII " + new URI("file:/a\u00e9%20b").toASCIIString());
        String[] auths = {"h", "host.com.", "-a.b", "a-.b", "a..b", "1.2.3.4", "1.2.3", "256.1.1.1", "1.2.3.a", "a.1", "1a", "h_1",
            "\u00e9", "h%41", "h%4", "u@h", "u:p@h", "@h", "u@", "u@@h", ":", "h:", "h:0080", "h:x", "h:99999999999", "h:80:90",
            "u%41@h", "u%4@h", "u h@h", "u[@h", "[::1]", "[::1]:8", "[::1", "[::1]x", "[]", "[1:2:3:4:5:6:7:8:9]", "[::ffff:1.2.3.4]",
            "[::g]", "[fe80::1%25eth0]", "[v1.x]", "u@[::1]:8", "[::1]:8%zz", "a b", "a;b", "a\u00a0b", "a b%zz", "a%zzb c", "a[b",
            "user@example.com:8080"};
        for (String a : auths) authority(a);
        System.out.println("ctor host check: " + make("x", "h^", "/p", null) + " | " + make(null, "::1", "/p", null));
        for (String s : new String[] {"http://", "//", "//?q", "http://?q", "file:#f", "a:#", "a:?q", "x://#a"}) parse(s);
        same("http://U@H:80/x", "http://u@h:80/x");
        same("http://u@H:80/x", "http://u@h:80/x");
        same("http://a_B/x", "http://a_b/x");
        same("file:/b", "x");
        same("file:/b", "file://a/");
        same("http://h:80/", "http://h:81/");
        same("mailto:a", "file:/a");
        same("http://u%41@h/", "http://u%41@H/");
        same("x:/a?Q", "x:/a?q");
    }
}
