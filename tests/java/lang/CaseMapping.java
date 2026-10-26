// TIMEOUT: 300
package lang;

public class CaseMapping {
    static String cps(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            b.append(Integer.toHexString(cp)).append(' ');
            i += Character.charCount(cp);
        }
        return b.toString().trim();
    }

    static void show(String label, String s) {
        String up = s.toUpperCase(), low = s.toLowerCase();
        System.out.println(label + ": [" + cps(s) + "] upper [" + cps(up) + "]" + (up == s ? " same" : "")
            + " lower [" + cps(low) + "]" + (low == s ? " same" : ""));
    }

    static long mix(long h, String s) {
        for (int i = 0; i < s.length(); i++) {
            h = (h ^ s.charAt(i)) * 0x100000001b3L;
        }
        return (h ^ 0x1ffff) * 0x100000001b3L;
    }

    public static void main(String[] args) {
        show("german", "Straße ẞ");
        show("empty", "");
        show("ascii", "Hello, World 123");
        show("already", "HELLO");
        show("latin1", "àéîõüÿ ÀÉ");
        show("mu", "µ ŉ ǰ ſ");
        show("dotted", "İ I i ı İstanbul");
        show("titlecase", "Ǆ ǅ ǆ Ǉ ǈ ǉ");
        show("greek", "ΑΒΓ αβγ ΐ ΰ ᾀ ᾈ ᾳ ᾼ ῷ");
        show("sigma1", "ΟΔΥΣΣΕΥΣ");
        show("sigma2", "Σ ΑΣ ΑΣ. Α.Σ ΑΣΑ ΑΣ Α");
        show("sigma3", "ΑΣ'Α Α'Σ 1Σ Α­Σ ΆΣ ΑΣ́");
        show("sigma4", "AΣB aΣ 中Σ ΣΣ ΑΣΣ");
        show("ligatures", "ﬀ ﬁ ﬂ ﬃ ﬄ ﬅ ﬆ և ﬓ");
        show("armenian", "ԱԲ աբ");
        show("supplementary", "𐐀 𐐨 x𐐀y");
        show("sigma-supp", "𐐀Σ A𐐀Σ Σ𐐀 AΣ𐐀");
        show("lone", "\ud800 a \udc00 \ud801");
        show("misc", "Ⅰ ⅰ Ⓐ ⓐ ª ʰ ͅ ẖ");
        show("cjk", "中文 あア Ａａ");

        // Every BMP code point in ten contexts; of the supplementary planes only those that can
        // hold assigned characters (0-3 and 14-16), in the four contexts that matter for them.
        long[] h = new long[10];
        java.util.Arrays.fill(h, 0xcbf29ce484222325L);
        String sigma = "Σ";
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            if (cp == 0x40000) {
                cp = 0xDFFFF;
                continue;
            }
            String c = new String(Character.toChars(cp));
            h[0] = mix(h[0], c.toUpperCase());
            h[1] = mix(h[1], c.toLowerCase());
            h[4] = mix(h[4], ("A" + sigma + c + "B").toLowerCase());
            h[7] = mix(h[7], (c + sigma).toLowerCase());
            if (cp > 0xFFFF) {
                continue;
            }
            h[2] = mix(h[2], (sigma + c).toLowerCase());
            h[3] = mix(h[3], ("A" + sigma + c).toLowerCase());
            h[5] = mix(h[5], ("A" + c + sigma).toLowerCase());
            h[6] = mix(h[6], (" " + c + sigma).toLowerCase());
            h[8] = mix(h[8], ("𐐀" + c + sigma).toLowerCase());
            h[9] = mix(h[9], (c + sigma + "𐐀").toLowerCase());
        }
        String[] names = {"upper", "lower", "S+c", "AS+c", "AS+cB", "A+c+S", " +c+S", "c+S", "D+c+S", "c+S+D"};
        for (int i = 0; i < h.length; i++) {
            System.out.println("hash " + names[i] + " " + Long.toHexString(h[i]));
        }
    }
}
