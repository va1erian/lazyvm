package java.lang;

/**
 * Case conversion of whole strings, the part of String.toUpperCase and toLowerCase that goes
 * beyond the one-to-one mapping of Character: characters that map to several (sharp s becomes
 * SS) and the Greek final sigma. The default locale is the root locale, so there is no Turkish or
 * Lithuanian tailoring.
 */
final class StringCase {
    private static final int SIGMA = 0x3A3;
    private static final char SMALL_SIGMA = (char) 0x3C3;
    private static final char FINAL_SIGMA = (char) 0x3C2;
    private static final int DOTTED_CAPITAL_I = 0x130;

    private StringCase() {
    }

    /** Code point index of the multi-code-point mapping of cp, or -1; see unicode.rs. */
    static native int special(int cp, boolean upper, int index);

    /** Moves a final-sigma automaton on reading cp; see unicode.rs. */
    static native int step(int state, int cp, boolean after);

    /** The sets of automaton states that decide a sigma; see unicode.rs. */
    static native int mask(int which);

    static String convert(String s, boolean upper) {
        int length = s.length();
        boolean sigmas = !upper && s.indexOf(SIGMA) >= 0;
        int accepting = sigmas ? mask(0) : 0;
        int before = 0;
        // The oracle returns a new string for U+0130 in upper case even though it maps to itself.
        boolean fresh = false;
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; ) {
            int cp = s.codePointAt(i);
            int next = i + Character.charCount(cp);
            if (!appendSpecial(sb, cp, upper)) {
                if (sigmas && cp == SIGMA) {
                    boolean last = (accepting >> before & 1) != 0 && !blockedAfter(s, next);
                    sb.append(last ? FINAL_SIGMA : SMALL_SIGMA);
                } else {
                    sb.appendCodePoint(upper ? Character.toUpperCase(cp) : Character.toLowerCase(cp));
                }
            }
            fresh |= upper && cp == DOTTED_CAPITAL_I;
            if (sigmas) {
                before = step(before, cp, false);
            }
            i = next;
        }
        String result = sb.toString();
        return !fresh && result.equals(s) ? s : result;
    }

    private static boolean appendSpecial(StringBuilder sb, int cp, boolean upper) {
        int k = 0;
        for (int c = special(cp, upper, 0); c >= 0; c = special(cp, upper, ++k)) {
            sb.appendCodePoint(c);
        }
        return k > 0;
    }

    // Reads the text after a sigma until the automaton reaches a state nothing leaves.
    private static boolean blockedAfter(String s, int from) {
        int notBlocked = mask(1);
        int terminal = mask(2);
        int state = 0;
        for (int j = from; j < s.length() && (terminal >> state & 1) == 0; ) {
            int cp = s.codePointAt(j);
            state = step(state, cp, true);
            j += Character.charCount(cp);
        }
        return (notBlocked >> state & 1) == 0;
    }
}
