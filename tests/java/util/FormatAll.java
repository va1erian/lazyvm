package util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Formatter;
import java.util.FormatterClosedException;
import java.util.Locale;

/** The Formatter class itself: destinations, closing, locales, errors from appendables. */
public class FormatAll {
    static void closed(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + ": ok");
        } catch (FormatterClosedException e) {
            System.out.println(what + ": closed " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        Formatter plain = new Formatter();
        plain.format("%s-%d", "a", 1).format("|%b", true);
        System.out.println(plain + " " + plain.out().getClass().getName() + " " + (plain.ioException() == null));

        StringBuilder sb = new StringBuilder("pre:");
        Formatter f = new Formatter(sb, Locale.US);
        f.format("a%db", 1);
        System.out.println(f.out() == sb);
        System.out.println(f.locale());
        System.out.println(f.format(Locale.ROOT, "%,.2f", 1234.5).locale());
        System.out.println(f);
        f.flush();
        f.close();
        f.close();
        closed("format", () -> f.format("x"));
        closed("toString", () -> f.toString());
        closed("out", () -> f.out());
        closed("locale", () -> f.locale());
        closed("flush", () -> f.flush());
        System.out.println(f.ioException());
        System.out.println(sb);

        Formatter nul = new Formatter((Appendable) null);
        System.out.println(nul.out().getClass().getName() + " " + nul.format("%s", "x"));
        System.out.println(new Formatter(Locale.ENGLISH).locale() + "|" + new Formatter(sb, null).locale());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(bytes);
        Formatter viaStream = new Formatter(ps);
        viaStream.format("%sé%d", "caf", 1);
        viaStream.flush();
        System.out.println(bytes.size() + " " + bytes.toString("UTF-8"));
        Formatter viaBytes = new Formatter(bytes);
        viaBytes.format("!").flush();
        System.out.println(bytes.size());

        Appendable bad = new Appendable() {
            public Appendable append(CharSequence c) throws IOException {
                throw new IOException("nope");
            }

            public Appendable append(CharSequence c, int s, int e) throws IOException {
                throw new IOException("nope2");
            }

            public Appendable append(char c) throws IOException {
                throw new IOException("nope3");
            }
        };
        Formatter k = new Formatter(bad);
        System.out.println(k.format("abc%d", 1) == k);
        System.out.println(k.ioException());

        System.out.println(String.format("%s|%5s|%-5s|", "a", "b", "c"));
        System.out.println(String.format(Locale.ROOT, "%,d", 1234567));
        System.out.println(String.format((Locale) null, "%,d", 1234567));
        System.out.println(String.format("%s", (Object[]) null));
        System.out.println(String.format("%2$s", (Object[]) null));
        System.out.println(String.format("no args"));
        System.out.println(String.format("%s %s", new Object[] {"x", "y"}));
        System.out.println(String.format("%s", new Object[] {"x", "y"}));
        System.out.println(String.format("%s", "x", "y"));
        System.out.printf("%d-%s%n", 1, "x");
        System.out.printf(Locale.US, "%.1f%n", 2.25);
        System.out.format("%5s|%n", "ab");
        System.out.format(Locale.ROOT, "%5s|%n", "ab");
        System.out.println(System.out.printf("") == System.out);
        System.out.println(String.format("%d%%", 50) + String.format("%n").equals(System.lineSeparator()));
        System.out.println(Locale.US + "|" + Locale.ROOT + "|" + Locale.ENGLISH);
        System.out.println(new Locale("EN", "us") + " " + new Locale("de") + " " + new Locale("de", "", "x")
                + " " + new Locale("", "DE") + " " + new Locale("EN", "us", "Var") + " [" + new Locale("", "", "v") + "]");
        System.out.println(Locale.US.getLanguage() + Locale.US.getCountry() + "|" + Locale.ROOT.getLanguage()
                + "|" + Locale.ENGLISH.getCountry() + "|" + new Locale("a", "b", "C").getVariant());
        System.out.println(new Locale("en").equals(Locale.ENGLISH) + " " + Locale.US.equals(Locale.ENGLISH)
                + " " + (new Locale("en", "US").hashCode() == Locale.US.hashCode()));
        System.out.println(Locale.UK + " " + Locale.GERMANY + " " + Locale.FRANCE + " " + Locale.CANADA + " "
                + Locale.GERMAN + " " + Locale.FRENCH + " " + Locale.JAPAN + " " + Locale.ITALY + " " + Locale.CHINA);
        try {
            new Locale(null);
        } catch (NullPointerException e) {
            System.out.println("npe");
        }
        System.out.println("done");
    }
}
