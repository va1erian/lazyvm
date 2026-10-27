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
    }
}
