package util;

import java.util.*;

/** StringTokenizer: delimiters, returned delimiters, switching delimiters, counts, surrogates, nulls. Rules T1-T8. */
public class Tokenizers {
    static String show(String t) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 || c > 0x7e) b.append(String.format("<%x>", (int) c)); else b.append(c);
        }
        return b.toString();
    }

    static String all(StringTokenizer t) {
        StringBuilder b = new StringBuilder(t.countTokens() + ":");
        while (t.hasMoreTokens()) b.append('[').append(show(t.nextToken())).append(']');
        return b.toString();
    }

    static void padTo(StringBuilder b, int n, char c) {
        while (b.length() < n) b.append(c);
    }

    /**
     * About 1100 chars with a token across 256, a surrogate pair across 512 and 1024, and a
     * delimiter run across 768, so scans cross any 256-char boundary in every state.
     */
    static String longText() {
        StringBuilder b = new StringBuilder();
        padTo(b, 254, 'a');
        b.append(" bcd");                    // token at 255-257
        padTo(b, 300, ' ');
        padTo(b, 511, 'e');
        b.append("😀f, ,");        // pair at 511-512
        while (b.length() < 760) b.append(b.length() % 7 == 0 ? ',' : 'i');
        padTo(b, 772, ' ');                  // delimiter run at 760-771
        b.append("😀，jk");
        padTo(b, 1023, 'l');
        b.append("😀 m，n");    // pair at 1023-1024
        return b.toString();
    }

    /** The count first, then each token as its length and its first and last char. */
    static String brief(StringTokenizer t) {
        StringBuilder b = new StringBuilder(t.countTokens() + ":");
        while (t.hasMoreTokens()) {
            String s = t.nextToken();
            b.append(' ').append(s.length()).append(show(s.substring(0, 1))).append(show(s.substring(s.length() - 1)));
        }
        return b.toString();
    }

    interface Op { Object run() throws Exception; }

    static String ex(Op r) {
        try {
            return show(String.valueOf(r.run()));
        } catch (Exception e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    static long seed = 0x70ce17L;

    static int rnd(int n) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) ((seed >>> 33) % n);
    }

    /** What the Javadoc's description gives, written plainly, for the fuzz to compare against (T1-T3). */
    static List<String> reference(String s, String delims, boolean ret) {
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int n = Character.charCount(cp);
            if (delims.indexOf(new String(Character.toChars(cp))) >= 0 && isWhole(delims, cp)) {
                if (ret) out.add(s.substring(i, i + n));
                i += n;
                continue;
            }
            int j = i;
            while (j < s.length()) {
                int c2 = s.codePointAt(j);
                if (delims.indexOf(new String(Character.toChars(c2))) >= 0 && isWhole(delims, c2)) break;
                j += Character.charCount(c2);
            }
            out.add(s.substring(i, j));
            i = j;
        }
        return out;
    }

    /** A delimiter code point counts only if it appears in `delims` as a whole code point. */
    static boolean isWhole(String delims, int cp) {
        for (int i = 0; i < delims.length(); ) {
            int d = delims.codePointAt(i);
            if (d == cp) return true;
            i += Character.charCount(d);
        }
        return false;
    }

    public static void main(String[] args) {
        // T1: the default delimiters are space, \t, \n, \r and \f only.
        System.out.println("default " + all(new StringTokenizer(" a\tb\nc\rd\fe  f g h\u000bi\u001cj ")));
        // T2: explicit delimiters, with and without returning them.
        System.out.println("delims " + all(new StringTokenizer("a,b;;c,", ",;")) + " " + all(new StringTokenizer("a,b;;c,", ",;", true)) + " " + all(new StringTokenizer("a,b", ",", false)) + " " + all(new StringTokenizer(",a", ",", true)));
        System.out.println("empty " + all(new StringTokenizer("")) + " " + all(new StringTokenizer("", ",", true)) + " " + all(new StringTokenizer(",,,", ",")) + " " + all(new StringTokenizer(",,,", ",", true)) + " " + all(new StringTokenizer("abc", "")) + " " + all(new StringTokenizer("abc", "", true)) + " " + all(new StringTokenizer("   ")));
        // T3: nextToken(delim) switches the delimiters for this and every later call.
        StringTokenizer t = new StringTokenizer("a b,c d", " ");
        System.out.println("switch " + t.nextToken() + "|" + t.nextToken(",") + "|" + t.nextToken() + "|" + ex(t::nextToken) + "|" + t.countTokens());
        t = new StringTokenizer("a,b c", ",", true);
        System.out.println("switch returned " + t.nextToken() + "|" + t.nextToken(" ") + "|" + t.nextToken() + "|" + t.nextToken() + "|" + ex(t::nextToken));
        t = new StringTokenizer("a b");
        System.out.println("switch to none " + t.nextToken("") + "|" + t.hasMoreTokens());
        t = new StringTokenizer("x:y z", ":");
        System.out.println("switch back " + t.nextToken() + "|" + t.nextToken(" ") + "|" + t.nextToken(":") + "|" + t.hasMoreTokens());
        // T4: countTokens counts what is left without moving.
        t = new StringTokenizer("a b c");
        System.out.println("count " + t.countTokens() + " " + t.nextToken() + " " + t.countTokens() + " " + t.hasMoreTokens() + " " + t.countTokens());
        System.out.println("count returned " + new StringTokenizer("a,b,c", ",", true).countTokens() + " " + new StringTokenizer(",,", ",", true).countTokens() + " " + new StringTokenizer(" a ").countTokens());
        // T5: the Enumeration methods, and running out.
        t = new StringTokenizer("ab");
        System.out.println("enumeration " + t.hasMoreElements() + " " + t.nextElement() + " " + t.hasMoreElements() + " " + ex(t::nextElement) + " " + ex(t::nextToken) + " " + ex(() -> new StringTokenizer("").nextToken(",")));
        // T5: a nextToken that finds nothing has moved past the delimiters; hasMoreTokens and countTokens do not move.
        StringTokenizer f1 = new StringTokenizer(",,", ",");
        StringTokenizer f2 = new StringTokenizer(",a,,", ",");
        StringTokenizer f3 = new StringTokenizer(",,", ",");
        StringTokenizer f4 = new StringTokenizer(",,", ",");
        System.out.println("failed next moves " + ex(f1::nextToken) + " then " + ex(() -> f1.nextToken("x"))
            + " | " + f2.nextToken() + " " + ex(f2::nextToken) + " then " + ex(() -> f2.nextToken("x"))
            + " | hasMore " + f3.hasMoreTokens() + " then " + ex(() -> f3.nextToken("x"))
            + " | count " + f4.countTokens() + " then " + ex(() -> f4.nextToken("x")));
        // T2-T4, T7 on a long text: each tokenizer counts to the end first and then reads from the
        // start, with tokens, pairs and delimiter runs across 256-char boundaries (longText).
        String longText = longText();
        System.out.println("long tokens " + brief(new StringTokenizer(longText)));
        System.out.println("long returned " + brief(new StringTokenizer(longText, " ,", true)));
        System.out.println("long fullwidth " + brief(new StringTokenizer(longText, " ，")));
        System.out.println("long pair delim " + brief(new StringTokenizer(longText, " ,😀", true)));
        StringTokenizer mixed = new StringTokenizer(longText, " ,");
        StringBuilder steps = new StringBuilder();
        for (int i = 0; mixed.hasMoreTokens(); i++) {
            steps.append(mixed.nextToken().length());
            if (i % 3 == 0) steps.append('/').append(mixed.countTokens());
            steps.append(' ');
        }
        System.out.println("long interleaved " + steps.toString().trim());
        // T6: null arguments.
        System.out.println("null string " + ex(() -> new StringTokenizer(null)) + " | " + ex(() -> new StringTokenizer(null, ",")) + " | " + ex(() -> new StringTokenizer(null, ",", true)));
        System.out.println("null delims " + ex(() -> new StringTokenizer("a b", null) != null) + " | " + ex(() -> new StringTokenizer("a b", null).hasMoreTokens()) + " | " + ex(() -> new StringTokenizer("a b", null).nextToken()) + " | " + ex(() -> new StringTokenizer("a b", null).countTokens()) + " | " + ex(() -> new StringTokenizer("", null).hasMoreTokens()) + " | " + ex(() -> new StringTokenizer("", null).countTokens()) + " | " + ex(() -> new StringTokenizer("", null, true).hasMoreTokens()) + " | " + ex(() -> new StringTokenizer("a b").nextToken(null)) + " | " + ex(() -> { StringTokenizer z = new StringTokenizer("a b"); try { z.nextToken(null); } catch (NullPointerException e) { } return z.nextToken(); }));
        // T7: a delimiter given as a surrogate pair matches only that pair; a lone half matches only a lone half.
        String pair = "😀";
        System.out.println("pair " + all(new StringTokenizer("a" + pair + "b\ud83dc\ude00d" + pair, pair)) + " " + all(new StringTokenizer("a" + pair + "b\ud83dc\ude00d", pair, true)));
        System.out.println("halves " + all(new StringTokenizer("a" + pair + "b\ud83dc", "\ud83d")) + " " + all(new StringTokenizer("a" + pair + "b\ude00c", "\ude00")) + " " + all(new StringTokenizer("a" + pair + "b,c", pair + ",", true)) + " " + all(new StringTokenizer(pair + pair, pair, true)));
        t = new StringTokenizer("a😀b c");
        System.out.println("switch to pair " + show(t.nextToken(pair)) + "|" + show(t.nextToken(" ")) + "|" + show(t.nextToken()) + "|" + t.hasMoreTokens());
        // T8: fuzz against the plain reading of the Javadoc (T1-T3), and a hash of everything.
        String alpha = "ab ,;😀\ud83d";
        String[] delimSets = {",", ", ", ";😀", "\ud83d", "", "ab"};
        int agree = 0;
        long hash = 0;
        for (int i = 0; i < 3000; i++) {
            StringBuilder b = new StringBuilder();
            int len = rnd(16);
            for (int k = 0; k < len; k++) b.append(alpha.charAt(rnd(alpha.length())));
            String s = b.toString();
            String d = delimSets[rnd(delimSets.length)];
            boolean ret = rnd(2) == 0;
            StringTokenizer z = new StringTokenizer(s, d, ret);
            int count = z.countTokens();
            List<String> got = new ArrayList<String>();
            while (z.hasMoreTokens()) got.add(z.nextToken());
            if (got.equals(reference(s, d, ret)) && count == got.size()) agree++;
            hash = hash * 31 + got.hashCode() + count;
        }
        System.out.println("fuzz agree " + agree + " of 3000, hash " + hash);
        System.out.println("done");
    }
}
