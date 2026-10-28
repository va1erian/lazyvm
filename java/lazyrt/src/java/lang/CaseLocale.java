package java.lang;

import java.util.Locale;

/**
 * The language-specific part of String case conversion: Turkish and Azeri keep the dotted and
 * dotless i apart, Lithuanian keeps the dot above a lower-case i when an accent follows. Every
 * other locale converts like the root locale, so it has no rules here. The marks that the rules
 * look past are listed in CaseMarks.
 */
final class CaseLocale {
    static final int ROOT = 0;
    static final int TURKIC = 1;
    static final int LITHUANIAN = 2;

    private static final int DOT_ABOVE = 0x307;
    private static final int DOTTED_CAPITAL_I = 0x130;
    private static final int DOTLESS_SMALL_I = 0x131;
    private static final int NONE = 0;
    private static final int SKIPPED = 1;
    private static final int ABOVE = 2;

    private CaseLocale() {
    }

    /** The marks as sorted lo, hi pairs; loaded on the first use of a tailored locale. */
    private static final class Sets {
        static final int[] ABOVE = parse(CaseMarks.ABOVE);
        static final int[] OTHER = parse(CaseMarks.OTHER);
        static final int[] SOFT_DOTTED = parse(CaseMarks.SOFT_DOTTED);

        private static int[] parse(String ranges) {
            String[] parts = ranges.split(",");
            int[] pairs = new int[parts.length * 2];
            for (int i = 0; i < parts.length; i++) {
                int dash = parts[i].indexOf('-');
                String lo = dash < 0 ? parts[i] : parts[i].substring(0, dash);
                pairs[2 * i] = Integer.parseInt(lo, 16);
                pairs[2 * i + 1] = dash < 0 ? pairs[2 * i] : Integer.parseInt(parts[i].substring(dash + 1), 16);
            }
            return pairs;
        }

        static boolean has(int[] pairs, int cp) {
            int lo = 0;
            int hi = pairs.length / 2 - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (cp < pairs[2 * mid]) {
                    hi = mid - 1;
                } else if (cp > pairs[2 * mid + 1]) {
                    lo = mid + 1;
                } else {
                    return true;
                }
            }
            return false;
        }
    }

    /** Which family of rules the locale's language selects. */
    static int of(Locale locale) {
        String language = locale.getLanguage();
        if (language.equals("tr") || language.equals("az")) {
            return TURKIC;
        }
        return language.equals("lt") ? LITHUANIAN : ROOT;
    }

    /**
     * Converts the code point cp at index i of s if the mode has a rule for it, appending the
     * result (possibly nothing), and says whether it did. Never called for ROOT, which has no rules.
     */
    static boolean append(StringBuilder sb, String s, int i, int cp, boolean upper, int mode,
            boolean changed) {
        if (mode == TURKIC) {
            return upper ? turkicUpper(sb, cp) : turkicLower(sb, s, i, cp);
        }
        return upper ? lithuanianUpper(s, i, cp, changed) : lithuanianLower(sb, s, i, cp);
    }

    private static boolean turkicUpper(StringBuilder sb, int cp) {
        if (cp != 'i') {
            return false;
        }
        sb.append((char) DOTTED_CAPITAL_I);
        return true;
    }

    private static boolean turkicLower(StringBuilder sb, String s, int i, int cp) {
        if (cp == 'I') {
            sb.append(dotFollows(s, i + 1) ? 'i' : (char) DOTLESS_SMALL_I);
        } else if (cp == DOTTED_CAPITAL_I) {
            sb.append('i');
        } else if (cp != DOT_ABOVE || !precededBy(s, i, false)) {
            return false;
        }
        return true;
    }

    // The dot above after a soft-dotted letter is dropped; the letter itself converts as usual.
    private static boolean lithuanianUpper(String s, int i, int cp, boolean changed) {
        return changed && cp == DOT_ABOVE && precededBy(s, i, true);
    }

    private static boolean lithuanianLower(StringBuilder sb, String s, int i, int cp) {
        if (cp == 'I' || cp == 'J' || cp == 0x12E) {
            if (!accentFollows(s, i + 1)) {
                return false;
            }
            sb.appendCodePoint(Character.toLowerCase(cp)).append((char) DOT_ABOVE);
            return true;
        }
        int accent = cp == 0xCC ? 0x300 : cp == 0xCD ? 0x301 : cp == 0x128 ? 0x303 : 0;
        if (accent == 0) {
            return false;
        }
        sb.append('i').append((char) DOT_ABOVE).append((char) accent);
        return true;
    }

    /** Whether upper-casing cp alters it; U+0130 counts although it maps to itself. */
    static boolean changes(int cp) {
        return Character.toUpperCase(cp) != cp || cp == DOTTED_CAPITAL_I || StringCase.special(cp, true, 0) >= 0;
    }

    private static int kind(int cp) {
        if (Sets.has(Sets.ABOVE, cp)) {
            return ABOVE;
        }
        return Sets.has(Sets.OTHER, cp) ? SKIPPED : NONE;
    }

    // A dot above comes next, past marks that sit elsewhere than above.
    private static boolean dotFollows(String s, int from) {
        for (int j = from; j < s.length(); ) {
            int cp = s.codePointAt(j);
            if (cp == DOT_ABOVE) {
                return true;
            }
            if (kind(cp) != SKIPPED) {
                return false;
            }
            j += Character.charCount(cp);
        }
        return false;
    }

    // Some mark above comes next, past marks that sit elsewhere than above.
    private static boolean accentFollows(String s, int from) {
        for (int j = from; j < s.length(); ) {
            int cp = s.codePointAt(j);
            int k = kind(cp);
            if (k != SKIPPED) {
                return k == ABOVE;
            }
            j += Character.charCount(cp);
        }
        return false;
    }

    // The nearest letter before index i, past marks that sit elsewhere than above, is a capital I
    // (Turkic) or a soft-dotted letter (Lithuanian).
    private static boolean precededBy(String s, int i, boolean softDotted) {
        for (int j = i; j > 0; ) {
            int cp = s.codePointBefore(j);
            if (kind(cp) != SKIPPED) {
                return softDotted ? Sets.has(Sets.SOFT_DOTTED, cp) : cp == 'I';
            }
            j -= Character.charCount(cp);
        }
        return false;
    }
}
