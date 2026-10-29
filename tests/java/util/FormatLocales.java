package util;

import java.util.Locale;

/** Decimal and grouping characters of Formatter under explicit locales. */
public class FormatLocales {
    static final String[] TAGS = {
        "en-US", "en-GB", "tr-TR", "tr", "de-DE", "de-CH", "de-AT", "fr-FR", "fr-CH", "fr-BE", "fr-CA", "it-IT",
        "it-CH", "es-ES", "es-MX", "es-AR", "pt-PT", "pt-BR", "nl-NL", "nl-BE", "ru-RU", "pl-PL", "ja-JP",
        "zh-CN", "zh-TW", "ko-KR", "sv-SE", "da-DK", "fi-FI", "nb-NO", "no-NO", "cs-CZ", "uk-UA", "ar-SA",
        "he-IL", "el-GR", "hu-HU", "ro-RO", "id-ID", "vi-VN", "bg-BG", "ca-ES", "sr-RS", "und", "xx-YY", "de-XX",
    };

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 128) {
                sb.append(c);
            } else {
                sb.append("{").append(Integer.toHexString(c)).append("}");
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        for (String t : TAGS) {
            Locale l = Locale.forLanguageTag(t);
            System.out.println(t + " " + esc(String.format(l, "%.2f|%,d|%,.3f|%e|%g|%10.1f|%d|%,d|%+,.2f|%(,.1f|%E|%G|%#.0f",
                    1234567.891, 1234567, 1234567.891, 1234.5, 1234.5, 2.25, -5, -1234567, 9876.543, -1234.55,
                    0.000123, 1e10, 3.0)));
        }
        System.out.println(String.format((Locale) null, "%,.2f", 1234567.891));
        System.out.println(String.format(Locale.ROOT, "%,.2f", 1234567.891));
        System.out.println(String.format(new Locale("tr", "TR"), "%.2f %,d", 1234.5, 1234567));
        System.out.println(String.format("%.2f %,d", 1234.5, 1234567));
        System.out.printf("%.2f %,d%n", 1234.5, 1234567);
        System.out.println(new java.util.Formatter().format("%,.1f", 1234.5));
        Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
        System.out.println(String.format("%.2f %,d", 1234.5, 1234567));
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        Locale.setDefault(Locale.Category.DISPLAY, Locale.FRANCE);
        System.out.println(String.format("%.2f %,d", 1234.5, 1234567));
        Locale.setDefault(Locale.FRANCE);
        System.out.println(esc(String.format("%.2f %,d", 1234.5, 1234567)));
    }
}
