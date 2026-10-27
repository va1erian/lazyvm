package util;

import java.util.Formattable;
import java.util.FormattableFlags;
import java.util.Formatter;
import java.util.Locale;

/** String-ish conversions: %s %S %b %h %c %n %%, argument indexes and Formattable. */
public class FormatStrings {
    /** Reports the arguments it is given, through the formatter it receives. */
    static class Fmt implements Formattable {
        public void formatTo(Formatter f, int flags, int width, int precision) {
            f.format("F[%d,%d,%d]", flags, width, precision);
        }

        public String toString() {
            return "Fstr";
        }
    }

    /** Pads its own text with FormattableFlags, the way a real implementation would. */
    static class Name implements Formattable {
        final String name;

        Name(String name) {
            this.name = name;
        }

        public void formatTo(Formatter f, int flags, int width, int precision) {
            String s = name;
            if ((flags & FormattableFlags.UPPERCASE) != 0) {
                s = s.toUpperCase();
            }
            if (precision >= 0 && precision < s.length()) {
                s = s.substring(0, precision);
            }
            StringBuilder sb = new StringBuilder();
            boolean left = (flags & FormattableFlags.LEFT_JUSTIFY) != 0;
            if (left) {
                sb.append(s);
            }
            for (int i = s.length(); i < width; i++) {
                sb.append('.');
            }
            if (!left) {
                sb.append(s);
            }
            if ((flags & FormattableFlags.ALTERNATE) != 0) {
                sb.append('#');
            }
            f.format("%s", sb);
        }
    }

    static void t(String f, Object... a) {
        try {
            System.out.println(f + " => [" + String.format(Locale.US, f, a) + "]");
        } catch (RuntimeException e) {
            System.out.println(f + " => " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        t("%s", "abc");
        t("%s", (Object) null);
        t("%s", 42);
        t("%s", 1.5);
        t("%s", 'c');
        t("%s", true);
        t("%s", 5L);
        t("%s", new StringBuilder("sb"));
        t("%s", new int[0].length);
        t("%S", "abc");
        t("%S", (Object) null);
        t("%10s|", "abc");
        t("%-10s|", "abc");
        t("%10S|", "abc");
        t("%.2s|", "abc");
        t("%.0s|", "abc");
        t("%.5s|", "abc");
        t("%.2S|", "abc");
        t("%5.1s|", "abc");
        t("%-5.1s|", "abc");
        t("%10.3S|", "hello");
        t("%10s|", (Object) null);
        t("%-10s|", (Object) null);
        t("%.3s|", (Object) null);
        t("%s and %s", "a", "b");
        t("%2$s %1$s", "a", "b");
        t("%2$s %s %s", "a", "b");
        t("%1$s %s %s", "a", "b");
        t("%3$s%<s%s", "a", "b", "c");
        t("%s%<s%<s", "a");
        t("%s %<5s|", "a");
        t("%s%<-5s|", "a");
        t("%0$s", "a");
        t("%1$s%1$s", "x");
        t("%s", "a", "ignored", "extra");
        t("%b", true);
        t("%b", false);
        t("%b", (Object) null);
        t("%b", "false");
        t("%b", "x");
        t("%b", 0);
        t("%b", Boolean.FALSE);
        t("%B", true);
        t("%B", (Object) null);
        t("%.1b", true);
        t("%.3B", false);
        t("%7b|", true);
        t("%-7b|", false);
        t("%7.2b|", true);
        t("%h", "hi");
        t("%H", "hi");
        t("%h", 1);
        t("%h", 'a');
        t("%h", -1);
        t("%h", Long.MAX_VALUE);
        t("%h", (Object) null);
        t("%H", (Object) null);
        t("%.1h", "hi");
        t("%10h|", "hi");
        t("%-10H|", "hi");
        t("%c", 'a');
        t("%c", 65);
        t("%C", 'a');
        t("%5c|", 'a');
        t("%-5c|", 'a');
        t("%c", '\u0000');
        t("%c", 'é');
        t("%C", 'é');
        t("%c", 0x1F600);
        t("%c", (Object) null);
        t("%s%s", "éè", "中文");
        t("%5s|", "é");
        t("%S", "é");
        t("%n");
        t("a%nb");
        t("%%");
        t("%5%|");
        t("%-5%|");
        t("100%%");
        t("%s%%", 5);
        t("%tY", (Object) null);
        t("%tY");
        t("%t");
        t("%T");
        t("%tq", 5L);
        t("%tY", "x");
        t("%s", new Fmt());
        t("%-6s|", new Fmt());
        t("%S", new Fmt());
        t("%#s", new Fmt());
        t("%-#S", new Fmt());
        t("%10.3s|", new Fmt());
        t("%-10.3S|", new Fmt());
        t("%s", new Name("Ada"));
        t("%8s", new Name("Ada"));
        t("%-8s", new Name("Ada"));
        t("%8S", new Name("Ada"));
        t("%8.2s", new Name("Ada"));
        t("%#8s", new Name("Ada"));
        t("%d", new Fmt());
        t("%b", new Fmt());
        t("%h", new Name("x").name);
        t("%5s|%-5s|", new Name("a"), new Name("b"));
        System.out.println("done");
    }
}
