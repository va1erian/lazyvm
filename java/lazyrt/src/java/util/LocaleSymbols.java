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

    /** The symbols of `l`, as of its language_COUNTRY, then its language, else the root's; null means none. */
    static LocaleSymbols of(Locale l) {
        if (l == null) {
            return ROOT;
        }
        String data = LocaleSymbolData.DATA;
        String lang = l.getLanguage();
        if (lang.equals("nb") || lang.equals("nn")) {
            lang = "no"; // Norwegian shares one set of symbols
        }
        int at = l.getCountry().isEmpty() ? -1 : data.indexOf("," + lang + "_" + l.getCountry() + ",");
        if (at < 0) {
            at = data.indexOf("," + lang + ",");
        }
        if (at < 0) {
            return ROOT;
        }
        int group = data.lastIndexOf(';', at) + 1;
        return new LocaleSymbols(data.charAt(group), data.charAt(group + 1));
    }
}
