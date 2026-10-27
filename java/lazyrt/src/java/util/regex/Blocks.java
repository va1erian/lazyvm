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

    private static CharClass find(String[] table, String name) {
        String key = "|" + name.toUpperCase() + "|";
        for (String entry : table) {
            int colon = entry.indexOf(':');
            if (("|" + entry.substring(0, colon) + "|").indexOf(key) >= 0) {
                return CharClass.cached(CharClass.ranges(entry.substring(colon + 1)));
            }
        }
        return null;
    }
}
