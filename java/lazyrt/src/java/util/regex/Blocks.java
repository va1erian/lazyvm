package java.util.regex;

/** Looks up \p{InBlock} and \p{IsScript} names in {@link UnicodeData}. */
final class Blocks {
    private Blocks() {
    }

    /**
     * Blocks are found by their constant name (BASIC_LATIN), by the display
     * name with a hyphen before a lone letter or digit (Latin-1 Supplement,
     * Latin Extended-A), or by either with spaces removed; case is ignored.
     */
    static CharClass block(String name) {
        String n = name.toUpperCase();
        for (String entry : UnicodeData.BLOCKS) {
            int colon = entry.indexOf(':');
            String constant = entry.substring(0, colon);
            String display = display(constant);
            if (n.equals(constant) || n.equals(display) || n.equals(display.replace(" ", ""))
                || alias(n, constant)) {
                return CharClass.cached(CharClass.ranges(entry.substring(colon + 1)));
            }
        }
        return null;
    }

    /** "LATIN_1_SUPPLEMENT" becomes "LATIN-1 SUPPLEMENT". */
    private static String display(String constant) {
        StringBuilder sb = new StringBuilder();
        int start = 0;
        while (start <= constant.length()) {
            int end = constant.indexOf('_', start);
            if (end < 0) {
                end = constant.length();
            }
            if (sb.length() > 0) {
                sb.append(end - start == 1 ? '-' : ' ');
            }
            sb.append(constant, start, end);
            start = end + 1;
        }
        return sb.toString();
    }

    /** Names the oracle accepts that are not derived from the constant name. */
    private static boolean alias(String n, String constant) {
        if (constant.equals("GREEK")) {
            return n.equals("GREEK AND COPTIC") || n.equals("GREEKANDCOPTIC");
        }
        if (constant.equals("CYRILLIC_SUPPLEMENTARY")) {
            return n.equals("CYRILLIC SUPPLEMENT") || n.equals("CYRILLICSUPPLEMENT");
        }
        if (constant.equals("COMBINING_MARKS_FOR_SYMBOLS")) {
            return n.equals("COMBINING DIACRITICAL MARKS FOR SYMBOLS")
                || n.equals("COMBININGDIACRITICALMARKSFORSYMBOLS");
        }
        return false;
    }

    /** A script by full name or ISO 15924 code, ignoring case. */
    static CharClass script(String name) {
        String n = name.toUpperCase();
        for (String entry : UnicodeData.SCRIPTS) {
            int colon = entry.indexOf(':');
            String names = "|" + entry.substring(0, colon) + "|";
            if (n.length() > 0 && names.indexOf("|" + n + "|") >= 0) {
                return CharClass.cached(CharClass.ranges(entry.substring(colon + 1)));
            }
        }
        return null;
    }
}
