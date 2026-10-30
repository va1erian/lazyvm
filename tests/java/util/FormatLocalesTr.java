// VMARGS: -Duser.language=tr -Duser.country=TR
package util;

import java.util.Locale;

/** String.format and printf under a tr_TR default locale. */
public class FormatLocalesTr {
    public static void main(String[] args) {
        System.out.println(String.format(new Locale("tr", "TR"), "%.2f %,d", 1234.5, 1234567));
        System.out.println(String.format("%.2f %,d %e %g", 1234.5, 1234567, 1234.5, 1234.5));
        System.out.printf("%.2f %,d%n", 1234.5, 1234567);
        System.out.println(String.format(Locale.US, "%.2f %,d", 1234.5, 1234567));
        System.out.println(new java.util.Formatter().format("%,.1f", 1234.5));
        System.out.println(String.format("%s %d", 1234.5, 1234567));
        System.out.println(String.format("%x %o %a", 255, 8, 0.5));
    }
}
