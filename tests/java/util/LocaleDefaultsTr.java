// VMARGS: -Duser.language=tr -Duser.country=TR
package util;

import java.util.Locale;

/** -Duser.language=tr -Duser.country=TR: the default, and what String case mapping does with it. */
public class LocaleDefaultsTr {
    public static void main(String[] args) {
        System.out.println(Locale.getDefault() + " " + Locale.getDefault(Locale.Category.FORMAT));
        System.out.println("title".toUpperCase() + " " + "TITLE".toLowerCase());
    }
}
