package java.util;

/** The decimal and grouping characters Formatter writes numbers with for a locale. */
final class LocaleSymbols {
    static final LocaleSymbols ROOT = new LocaleSymbols('.', ',');

    final char decimal;
    final char grouping;

    private LocaleSymbols(char decimal, char grouping) {
        this.decimal = decimal;
        this.grouping = grouping;
    }

    /**
     * The symbols of `l`: its script-specific entry if the table has one, else its
     * language_COUNTRY, then its language, else the root's. Null means no localization.
     */
    static LocaleSymbols of(Locale l) {
        if (l == null) {
            return ROOT;
        }
        String lang = l.getLanguage();
        if (lang.equals("nb") || lang.equals("nn")) {
            lang = "no"; // Norwegian shares one set of symbols
        }
        String country = l.getCountry();
        LocaleSymbols found = null;
        if (!l.getScript().isEmpty()) {
            found = find(lang + "_" + country + "_#" + l.getScript());
            found = found != null ? found : find(lang + "__#" + l.getScript());
        }
        if (found == null && !country.isEmpty()) {
            found = find(lang + "_" + country);
        }
        if (found == null) {
            found = find(lang);
        }
        return found != null ? found : ROOT;
    }

    // Walks the groups of the table (see LocaleSymbolData) and compares whole keys.
    private static LocaleSymbols find(String key) {
        String data = LocaleSymbolData.DATA;
        int group = 0;
        while (group < data.length()) {
            int end = data.indexOf(';', group);
            end = end < 0 ? data.length() : end;
            for (int k = group + 3; k < end; ) {
                int comma = data.indexOf(',', k);
                if (comma - k == key.length() && data.startsWith(key, k)) {
                    return new LocaleSymbols(data.charAt(group), data.charAt(group + 1));
                }
                k = comma + 1;
            }
            group = end + 1;
        }
        return null;
    }
}
