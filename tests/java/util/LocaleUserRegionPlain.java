// VMARGS: -Duser.region=FR -Duser.country.format=GB -Duser.language.display=es
package util;

import java.util.Locale;

/** The default locales under -Duser.region=FR -Duser.country.format=GB -Duser.language.display=es. */
public class LocaleUserRegionPlain {
    static String show(Locale l) {
        return "[" + l + "] " + l.toLanguageTag() + " " + l.getScript();
    }

    public static void main(String[] args) {
        System.out.println(show(Locale.getDefault()));
        System.out.println(show(Locale.getDefault(Locale.Category.FORMAT)));
        System.out.println(show(Locale.getDefault(Locale.Category.DISPLAY)));
        System.out.println(String.format("%,.2f", 1234567.891));
    }
}
