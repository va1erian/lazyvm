package java.util.regex;

/**
 * Looks up \p{InBlock} and \p{IsScript} names in {@link UnicodeData}. Each
 * table entry lists every name the oracle accepts for it (RegexUnicode pins
 * the spellings); matching ignores case and nothing else.
 */
final class Blocks {
    private Blocks() {
    }

    static CharClass block(String name) {
        CharClass cc = find(UnicodeData.BLOCKS, name);
        return cc == null ? null : cc.markCategoryOrBlock();
    }

    static CharClass script(String name) {
        return find(UnicodeData.SCRIPTS, name);
    }

    /**
     * The name in upper or lower case, as the oracle folds property, block and script names: by
     * Unicode case mapping of the whole string (a dotless i folds to I, a capital dotted I lowers
     * to i and a combining dot), the same under every default locale (RegexNamesLocale). lazyrt's
     * String methods without a Locale do not read the default locale; once they do, this must
     * pass Locale.ROOT.
     */
    static String foldName(String name, boolean upper) {
        return upper ? name.toUpperCase() : name.toLowerCase();
    }

    private static CharClass find(String[] table, String name) {
        String key = "|" + foldName(name, true) + "|";
        for (String entry : table) {
            int colon = entry.indexOf(':');
            if (("|" + entry.substring(0, colon) + "|").indexOf(key) >= 0) {
                return CharClass.cached(CharClass.ranges(entry.substring(colon + 1)));
            }
        }
        return null;
    }
}
