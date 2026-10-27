package util;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Case folding under CASE_INSENSITIVE with and without UNICODE_CASE: which characters a literal, a
 * class of one character and a range of one character accept. Each row is one bit per sample
 * character in SAMPLES (1 = the pattern built from the row's character matches it).
 */
public class RegexFolding {
    static final int[] SAMPLES = {'a', 'A', 'k', 'K', 's', 'S', 'i', 'I', 0xB5, 0xC5, 0xE5, 0xFF, 0x178, 0xDF, 0x1E9E, 0x130, 0x131, 0x17F,
        0x212A, 0x212B, 0x2126, 0x3A9, 0x3C9, 0x1C4, 0x1C5, 0x1C6, 0x3C2, 0x3C3, 0x3A3, 0x3D0, 0x392, 0x3B2, 0x345, 0x3B9, 0x399, 0x1FBE, 0x398,
        0x3B8, 0x3D1, 0x3F4, 0x1E9B, 0x1E60, 0x1E61, 0x3F5, 0x10400, 0x10428};

    static String str(int cp) {
        return new String(Character.toChars(cp));
    }

    static String bits(String form, int c, int flags) {
        StringBuilder sb = new StringBuilder();
        try {
            Pattern p = Pattern.compile(form.replace("%", str(c)), flags);
            for (int x : SAMPLES) {
                String text = form.endsWith("a") ? str(x) + "a" : str(x);
                sb.append(p.matcher(text).matches() ? '1' : '0');
            }
        } catch (PatternSyntaxException e) {
            sb.append("error");
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        int ci = Pattern.CASE_INSENSITIVE;
        int cu = ci | Pattern.UNICODE_CASE;
        String[] names = {"literal", "class of one", "range of one", "negated class", "range to the end", "literal then a", "class then a"};
        String[] forms = {"%", "[%]", "[%-%]", "[^%]", "[%-￿]", "%a", "[%]a"};
        for (int flags : new int[] {ci, cu}) {
            System.out.println("== flags " + flags);
            for (int f = 0; f < forms.length; f++) {
                System.out.println("-- " + names[f]);
                for (int c : SAMPLES) {
                    if (c > 0xFFFF && f == 4) {
                        continue;
                    }
                    System.out.println(Integer.toHexString(c) + " " + bits(forms[f], c, flags));
                }
            }
        }
        System.out.println("== a plain character in a run (an empty right side of && keeps the last item)");
        for (int flags : new int[] {0, ci, cu}) {
            StringBuilder sb = new StringBuilder("flags " + flags + " apart:");
            for (int x = 0; x < 256; x++) {
                if ("\\[]&^- #\t\n\r\u000b\f".indexOf(x) >= 0) {
                    continue;
                }
                if (!Pattern.compile("[" + (char) x + "a&&]", flags).matcher(String.valueOf((char) x)).matches()) {
                    sb.append(' ').append(Integer.toHexString(x));
                }
            }
            System.out.println(sb);
        }
        System.out.println("done");
    }
}
