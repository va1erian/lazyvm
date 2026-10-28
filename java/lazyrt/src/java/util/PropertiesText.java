package java.util;

import java.io.BufferedWriter;
import java.io.IOException;

/** What Properties.store writes (docs/design/util-text.md P9-P12): escaped pairs and comments. */
final class PropertiesText {
    private PropertiesText() {
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * P9, P10: a key escapes every space, a value only its first char's; `ascii` (the stream form)
     * also writes chars outside printable ASCII as \\uXXXX.
     */
    static String escape(String s, boolean key, boolean ascii) {
        StringBuilder b = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '\t': b.append("\\t"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\f': b.append("\\f"); break;
                case '=': case ':': case '#': case '!': b.append('\\').append(c); break;
                case ' ':
                    b.append(key || i == 0 ? "\\ " : " ");
                    break;
                default:
                    if (ascii && (c < 0x20 || c > 0x7e)) {
                        unicode(b, c);
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    private static void unicode(StringBuilder b, char c) {
        b.append("\\u").append(HEX[c >> 12]).append(HEX[(c >> 8) & 15]).append(HEX[(c >> 4) & 15]).append(HEX[c & 15]);
    }

    /**
     * P12: a '#' first; each break ends a line, and the next gets a '#' unless it starts with '#'
     * or '!'. Chars above U+00FF are escaped whatever the target.
     */
    static void writeComments(BufferedWriter out, String comments) throws IOException {
        StringBuilder b = new StringBuilder("#");
        int n = comments.length();
        for (int i = 0; i < n; i++) {
            char c = comments.charAt(i);
            if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && comments.charAt(i + 1) == '\n') {
                    i++;
                }
                out.write(b.toString());
                out.newLine();
                b.setLength(0);
                char first = i + 1 < n ? comments.charAt(i + 1) : 0;
                if (first != '#' && first != '!') {
                    b.append('#');
                }
            } else if (c > 0xff) {
                unicode(b, c);
            } else {
                b.append(c);
            }
        }
        out.write(b.toString());
        out.newLine();
    }
}
