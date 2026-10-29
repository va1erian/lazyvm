// VMARGS: -Duser.language=sr -Duser.country=RS -Duser.variant=x -Duser.script=Latn
package util;

import java.util.Locale;

/** The default locale built from the user.* properties. */
public class LocaleDefaults {
    public static void main(String[] args) {
        Locale l = Locale.getDefault();
        System.out.println(l + " " + l.getScript() + " " + l.toLanguageTag());
        System.out.println(Locale.getDefault(Locale.Category.FORMAT) + " "
                + Locale.getDefault(Locale.Category.DISPLAY));
        System.out.println(System.getProperty("user.language") + " " + System.getProperty("user.country")
                + " [" + System.getProperty("user.variant") + "] " + System.getProperty("user.script"));
    }
}
