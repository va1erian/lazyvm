package util;

import java.net.URI;

/** URI.hashCode values, which LazyVM matches to the oracle's (the Javadoc only asks for consistency). */
public class UriHash {
    static final String[] URIS = {
        "a:b", "a:b#f", "A:b", "a:B", "a:b%2a", "a:b%2A", "#f", "/p", "/P", "?q", "/p?q", "/p?q#f",
        "//h", "//H", "//h:80", "//h:81", "//u@h", "//u@h:80", "//h/p", "//h/p?q#f",
        "http://Host:8080/Path?Query#Frag", "http://host:8080/Path?Query#Frag",
        "http://host:8080/Path%2a?Query#Frag", "", "x", "//a_b", "//[::1]", "//[::1]:80", "//h:",
        "file:///x", "mailto:a@b.c", "//reg_name/p", "//a@b@c", "s://u:p@h", "//h?", "/p?", "//h#",
        "a:b?c", "//\u00fcn\u00ef", "//h/%c3%a9", "//h/%C3%A9", "//h/\u00e9", "a:b%c3%a9", "a:b%C3%A9",
        "HTTP://EXAMPLE.COM/a%2fb", "http://example.com/a%2Fb", "x:y:z", "//h:0", "//h:65535",
    };

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < URIS.length; i++) {
            URI u = new URI(URIS[i]);
            System.out.println("<" + URIS[i] + "> " + u.hashCode());
        }
        System.out.println(new URI("HTTP://EXAMPLE.COM/a%2fb").hashCode() == new URI("http://example.com/a%2Fb").hashCode());
        System.out.println(new URI("//H").equals(new URI("//h")) + " " + new URI("/P").equals(new URI("/p")));
    }
}
