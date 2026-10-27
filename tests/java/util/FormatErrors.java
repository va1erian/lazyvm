package util;

import java.util.Locale;

/** Malformed specifiers: unknown conversions, duplicate or illegal flags, missing widths, bad precisions. */
public class FormatErrors {
    static void t(String f, Object... a) {
        try {
            System.out.println(f + " => [" + String.format(Locale.US, f, a) + "]");
        } catch (RuntimeException e) {
            System.out.println(f + " => " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        t("%");
        t("abc%");
        t("%-");
        t("%5");
        t("%5.");
        t("%.3");
        t("%.f", 1.5);
        t("%..2f", 1.5);
        t("%y");
        t("%-y");
        t("%!");
        t("%#");
        t("%-5$s", "a");
        t("%5<s|", "a");
        t("%1$1$s", "a");
        t("%F", 1.5);
        t("%i", 1);
        t("%tq", 1L);
        t("%Tq", 1L);
        t("%--d", 1);
        t("%++d", 1);
        t("%  d", 1);
        t("%00d", 1);
        t("%##x", 1);
        t("%,,d", 1);
        t("%((d", 1);
        t("%<<s", "a");
        t("%-d", 1);
        t("%-s", "a");
        t("%-b", true);
        t("%-c", 'a');
        t("%-%");
        t("%-+ d", 1);
        t("%0d", 1);
        t("%0,d", 1);
        t("%0f", 1.5);
        t("%0e", 1.5);
        t("%0.3f", 1.5);
        t("%0(d", -1);
        t("%0-d", 1);
        t("%-0d", 1);
        t("%-010d|", 1);
        t("%-010f|", 1.5);
        t("%-010x|", 1);
        t("%-07E|", 1.5);
        t("%+ d", 1);
        t("% +d", 1);
        t("%+ f", 1.5);
        t("%+ 5.2e", 1.5);
        t("%.2d", 1);
        t("%.0d", 1);
        t("%5.2d", 1);
        t("%.3x", 5);
        t("%.3o", 5);
        t("%.3X", 5);
        t("%.3c", 5);
        t("%.3C", 5);
        t("%.3n");
        t("%.3%");
        t("%.3tY", 1L);
        t("%5n");
        t("%-n");
        t("%#n");
        t("%0n");
        t("%5.2n");
        t("%#%");
        t("%+%");
        t("%0%");
        t("% %");
        t("%,%");
        t("%(%");
        t("%#d", 1);
        t("%#b", true);
        t("%#B", true);
        t("%#h", 1);
        t("%#c", 'a');
        t("%#e", 1.5);
        t("%#f", 1.5);
        t("%#g", 1.5);
        t("%#a", 1.5);
        System.out.println("done");
    }
}
