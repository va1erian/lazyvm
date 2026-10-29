// VMARGS: -Duser.language= -Duser.language.format=
package util;

import java.util.Locale;

/** The default locales under -Duser.language= -Duser.language.format=. */
public class LocaleUserEmpty {
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
