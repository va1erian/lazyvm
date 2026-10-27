package util;

import java.util.Locale;

/** The %a conversion. */
public class FormatHexFloats {
    static void t(String f, Object... a) {
        try {
            System.out.println(f + " => [" + String.format(Locale.US, f, a) + "]");
        } catch (RuntimeException e) {
            System.out.println(f + " => " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        t("%a", 1.0);
        t("%a", 0.5);
        t("%a", 0.1);
        t("%a", 255.0);
        t("%a", -2.5);
        t("%a", 0.0);
        t("%a", -0.0);
        t("%a", 1e300);
        t("%a", 1e-300);
        t("%a", Double.MIN_VALUE);
        t("%a", Double.MIN_NORMAL);
        t("%a", Double.MAX_VALUE);
        t("%a", Double.POSITIVE_INFINITY);
        t("%a", Double.NEGATIVE_INFINITY);
        t("%a", 1e-310);
        t("%a", -1e-310);
        t("%a", 2.5e-320);
        t("%a", 1.0f);
        t("%a", 0.1f);
        t("%a", Float.MIN_VALUE);
        t("%a", Float.MAX_VALUE);
        t("%A", 255.5);
        t("%A", Double.NaN);
        t("%A", Double.NEGATIVE_INFINITY);
        t("%.0a", 1.0);
        t("%.0a", 1.5);
        t("%.0a", 3.0);
        t("%.1a", 1.0);
        t("%.1a", 1.99);
        t("%.1a", 1.03125);
        t("%.1a", 1.09375);
        t("%.1a", 1.0625);
        t("%.1a", 1.1875);
        t("%.1a", 1.96875);
        t("%.1a", 1.9375);
        t("%.1a", 1.9);
        t("%.2a", 1.0);
        t("%.2a", 0.0);
        t("%.0a", 0.0);
        t("%.0a", -0.0);
        t("%.3a", 0.1);
        t("%.3a", 1.0625);
        t("%.5a", 100.0);
        t("%.5a", 1e300);
        t("%.5a", 1e-300);
        t("%.13a", 0.1);
        t("%.13a", 1e-300);
        t("%.14a", 0.1);
        t("%.14a", 1e300);
        t("%.20a", 1.0);
        t("%.1a", Double.MIN_VALUE);
        t("%.0a", Double.MIN_VALUE);
        t("%.12a", Double.MIN_VALUE);
        t("%.13a", Double.MIN_VALUE);
        t("%.1a", 1e-310);
        t("%.3a", 1e-310);
        t("%.12a", 1e-310);
        t("%.13a", 1e-310);
        t("%.14a", 1e-310);
        t("%.2a", 2.5e-320);
        t("%.3a", Double.MIN_NORMAL);
        t("%.3a", Float.MIN_VALUE);
        t("%20a|", 1.0);
        t("%-20a|", 1.0);
        t("%020a|", 1.0);
        t("%020a|", -1.0);
        t("%030a", 1.0);
        t("%030a", 1024.0);
        t("%030a", 1e-300);
        t("%+030a", 1.5e10);
        t("%030a", 1.5e-310);
        t("%030.3a", 1.5);
        t("%030.3a", 1e300);
        t("%030.14a", 1.5e-310);
        t("%+a", 1.0);
        t("%+a", 0.0);
        t("%+a", -0.0);
        t("% a", 1.0);
        t("% a", -1.0);
        t("%+a", Double.POSITIVE_INFINITY);
        t("%+a", Double.NaN);
        t("%010a|", Double.POSITIVE_INFINITY);
        t("%10a|", Double.NaN);
        t("%-10A|", Double.NEGATIVE_INFINITY);
        t("%#a", 1.0);
        t("%#a", 3.0);
        t("%#.0a", 1.0);
        t("%25.3a|", 3.14159);
        t("%-25.3A|", 3.14159);
        t("%025.3a|", 3.14159);
        t("%+025.3a|", 3.14159);
        t("% 025.3A|", -3.14159);
        t("%(a", -1.0);
        t("%,a", 1.0);
        t("%-010a|", 1.0);
        System.out.println("done");
    }
}
