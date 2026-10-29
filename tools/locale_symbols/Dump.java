import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Prints, for every locale the Java 8 oracle has number symbols for (language, or
 * language and country, without script or variant), the decimal and grouping
 * characters that String.format puts in "%,.1f" of 1234567.5. tools/locale_symbols.py
 * turns the output into java/lazyrt/src/java/util/LocaleSymbolData.java.
 *
 * Line format: "key TAB decimal-hex TAB grouping-hex", key being language or language_COUNTRY.
 * A locale whose zero digit is not '0' is printed with "zero" in place of the characters.
 */
public class Dump {
    public static void main(String[] args) {
        for (Locale l : DecimalFormatSymbols.getAvailableLocales()) {
            if (!l.getVariant().isEmpty() || !l.getScript().isEmpty() || l.getLanguage().isEmpty()) {
                continue;
            }
            String key = l.getCountry().isEmpty() ? l.getLanguage() : l.getLanguage() + "_" + l.getCountry();
            String zero = String.format(l, "%d", 0);
            String s = String.format(l, "%,.1f", 1234567.5);
            if (!zero.equals("0") || s.length() != 11 || s.charAt(1) != s.charAt(5)) {
                System.out.println(key + "\tzero");
                continue;
            }
            System.out.println(key + "\t" + Integer.toHexString(s.charAt(9)) + "\t" + Integer.toHexString(s.charAt(1)));
        }
    }
}
