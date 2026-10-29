// VMARGS: -Duser.language=fr -Duser.language.format=de -Duser.country.format=AT -Duser.script.format=latn -Duser.variant.display=zz
package util;

import java.util.Locale;

/** The default locales under -Duser.language=fr -Duser.language.format=de -Duser.country.format=AT -Duser.script.format=latn -Duser.variant.display=zz. */
public class LocaleUserCategories {
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
