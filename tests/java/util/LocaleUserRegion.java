// VMARGS: -Duser.region=FR_CA -Duser.script=hant -Duser.country=DE -Duser.variant=zz
package util;

import java.util.Locale;

/** The default locales under -Duser.region=FR_CA -Duser.script=hant -Duser.country=DE -Duser.variant=zz. */
public class LocaleUserRegion {
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
