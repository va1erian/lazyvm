package util;

import java.util.*;

/** Flag/conversion mismatches, wrong argument types, missing arguments, and the exception classes themselves. */
public class FormatArgErrors {
    static void t(String f, Object... a) {
        try {
            System.out.println(f + " => [" + String.format(Locale.US, f, a) + "]");
        } catch (RuntimeException e) {
            System.out.println(f + " => " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        t("%,e", 1.5);
        t("%,E", 1.5);
        t("%,a", 1.5);
        t("%,(a", 1.5);
        t("%(a", 1.5);
        t("%+s", "a");
        t("% s", "a");
        t("%0s", "a");
        t("%,s", "a");
        t("%(s", "a");
        t("%+b", true);
        t("%0c", 'a');
        t("%+c", 'a');
        t("%(h", 1);
        t("%#s", "a");
        t("%#S", 1);
        t("%#s");
        t("%+x", 255);
        t("% x", 255);
        t("%,x", 255);
        t("%(x", 255);
        t("%+(x", 255);
        t("%+o", 255);
        t("%,o", 255);
        t("%(X", 255);
        t("%x", 1.5);
        t("%d", "x");
        t("%d", 1.5);
        t("%d", 1.5f);
        t("%d", 'c');
        t("%d", true);
        t("%o", "7");
        t("%X", 2.5);
        t("%c", 1.5);
        t("%c", "s");
        t("%c", 5L);
        t("%c", true);
        t("%c", -1);
        t("%c", 0x110000);
        t("%c", Integer.MAX_VALUE);
        t("%c", (byte) -1);
        t("%c", (short) -2);
        t("%C", -1);
        t("%e", 1);
        t("%f", 1L);
        t("%g", 'a');
        t("%G", "s");
        t("%a", 1);
        t("%A", true);
        t("%E", (byte) 1);
        t("%f", new Object[] {"a"});
        t("%d");
        t("%s %s", "a");
        t("%2$s", "a");
        t("%3$s %1$s", "a", "b");
        t("%<s", "a");
        t("%s %<s %<s %s", "a");
        t("%-5s|");
        t("%-5$s", "a");
        t("%2147483648$s", "a");
        t("%99999999999d", 1);
        t("%.99999999999d", 1);
        t("%d %.2d", 1, 2);
        t("%d %y", 1);
        t("%d %d %y", 1);
        t("%y %-d");
        t("%-d %y");
        t("%d %s", "a", 2);
        t("%tY");
        t("%1$tY");
        String[] out = new String[1];
        StringBuilder sb = new StringBuilder("pre:");
        Formatter f = new Formatter(sb);
        try {
            f.format("x%dy%sz%d", 2, "s", "bad");
        } catch (IllegalFormatException e) {
            System.out.println(e.getClass().getName() + ": " + e.getMessage() + " after " + sb);
        }
        try {
            f.format("ok%dnot%y", 1);
        } catch (IllegalFormatException e) {
            System.out.println(e.getClass().getName() + ": " + e.getMessage() + " after " + sb);
        }
        IllegalFormatException[] made = {
            new DuplicateFormatFlagsException("-"),
            new FormatFlagsConversionMismatchException("#", 'd'),
            new IllegalFormatCodePointException(65),
            new IllegalFormatCodePointException(-5),
            new IllegalFormatConversionException('d', String.class),
            new IllegalFormatFlagsException("+ "),
            new IllegalFormatPrecisionException(-4),
            new IllegalFormatWidthException(-4),
            new MissingFormatArgumentException("%5d"),
            new MissingFormatWidthException("%-d"),
            new UnknownFormatConversionException("q"),
            new UnknownFormatFlagsException("zz"),
        };
        for (int i = 0; i < made.length; i++) {
            System.out.println(made[i].getClass().getName() + ": " + made[i].getMessage()
                    + " / " + (made[i] instanceof IllegalArgumentException));
        }
        System.out.println(new DuplicateFormatFlagsException("-").getFlags());
        FormatFlagsConversionMismatchException m = new FormatFlagsConversionMismatchException("#", 'd');
        System.out.println(m.getFlags() + m.getConversion());
        System.out.println(new IllegalFormatCodePointException(65).getCodePoint());
        IllegalFormatConversionException c = new IllegalFormatConversionException('d', String.class);
        System.out.println(c.getConversion() + " " + c.getArgumentClass());
        System.out.println(new IllegalFormatFlagsException("+ ").getFlags());
        System.out.println(new IllegalFormatPrecisionException(7).getPrecision());
        System.out.println(new IllegalFormatWidthException(8).getWidth());
        System.out.println(new MissingFormatArgumentException("%5d").getFormatSpecifier());
        System.out.println(new MissingFormatWidthException("%-d").getFormatSpecifier());
        System.out.println(new UnknownFormatConversionException("q").getConversion());
        System.out.println(new UnknownFormatFlagsException("zz").getFlags());
        System.out.println(new FormatterClosedException() instanceof IllegalStateException);
        System.out.println(new FormatterClosedException().getMessage());
        try {
            new DuplicateFormatFlagsException(null);
        } catch (NullPointerException e) {
            System.out.println("npe1");
        }
        try {
            new IllegalFormatConversionException('c', null);
        } catch (NullPointerException e) {
            System.out.println("npe2");
        }
        try {
            new FormatFlagsConversionMismatchException(null, 'c');
        } catch (NullPointerException e) {
            System.out.println("npe3");
        }
        try {
            String.format(null, 1);
        } catch (NullPointerException e) {
            System.out.println("npe4");
        }
        System.out.println("done");
    }
}
