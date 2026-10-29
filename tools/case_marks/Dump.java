import java.util.Locale;

/**
 * Prints the three code point sets that the Lithuanian and Turkic case rules of
 * String.toLowerCase(Locale) and toUpperCase(Locale) depend on, found by asking
 * the Java 8 oracle: A (marks that stop a dot search and carry the above
 * position), O (other marks, which a dot search skips) and S (the letters whose
 * dot above is dropped in upper case, probed after a letter that changes case). tools/case_marks.py turns the output
 * into java/lazyrt/src/java/lang/CaseMarks.java.
 *
 * Line format: "A|O|S TAB lo-hi,lo-hi".
 */
public class Dump {
    private static final Locale LT = new Locale("lt");
    private static final String DOT = "\u0307";

    private static boolean valid(int cp) {
        return cp < 0xD800 || cp >= 0xE000;
    }

    private static String ranges(boolean[] set) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < set.length; i++) {
            if (!set[i]) {
                continue;
            }
            int j = i;
            while (j + 1 < set.length && set[j + 1]) {
                j++;
            }
            sb.append(sb.length() > 0 ? "," : "").append(Integer.toHexString(i));
            if (j > i) {
                sb.append('-').append(Integer.toHexString(j));
            }
            i = j;
        }
        return sb.toString();
    }

    // A capital I gains a dot when a mark above follows, directly or past marks that sit elsewhere.
    private static boolean dotted(String s) {
        return s.toLowerCase(LT).startsWith("i" + DOT);
    }

    public static void main(String[] args) {
        boolean[] above = new boolean[0x110000];
        boolean[] marks = new boolean[0x110000];
        boolean[] soft = new boolean[0x110000];
        for (int cp = 0; cp < 0x110000; cp++) {
            if (!valid(cp)) {
                continue;
            }
            String c = new String(Character.toChars(cp));
            above[cp] = dotted("I" + c);
            marks[cp] = dotted("I" + c + "\u0301");
            // A cased prefix is needed: the dot is only dropped once something has changed case.
            String probe = "a" + c + DOT;
            soft[cp] = !probe.toUpperCase(LT).equals(probe.toUpperCase(Locale.ROOT));
        }
        boolean[] rest = new boolean[0x110000];
        for (int cp = 0; cp < rest.length; cp++) {
            rest[cp] = marks[cp] && !above[cp];
        }
        System.out.println("A\t" + ranges(above));
        System.out.println("O\t" + ranges(rest));
        System.out.println("S\t" + ranges(soft));
    }
}
