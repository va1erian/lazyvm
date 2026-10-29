package util;

import java.util.Locale;

/** The default locales are fixed at startup: later changes to user.* do not move them. */
public class LocaleDefaultsFixed {
    public static void main(String[] args) {
        System.setProperty("user.language", "fr");
        System.setProperty("user.country", "FR");
        System.setProperty("user.language.format", "de");
        System.out.println(Locale.getDefault() + " " + Locale.getDefault(Locale.Category.FORMAT) + " "
                + Locale.getDefault(Locale.Category.DISPLAY));
        System.out.println(String.format("%,.2f", 1234567.891));
        System.out.println(new java.util.Formatter().format("%,.2f", 1234567.891));
    }
}
