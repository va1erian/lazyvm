package lang;

import java.io.File;
import java.net.URI;
import java.util.Locale;

public class StringCaseLocale {
    static final Locale[] LOCALES = {
        Locale.ROOT, Locale.ENGLISH, new Locale("tr"), new Locale("az", "AZ"), new Locale("lt"),
        new Locale("el"), Locale.GERMAN, new Locale("nl", "NL"),
    };

    static String cps(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            b.append(i > 0 ? " " : "").append(String.format("U+%04X", cp));
            i += Character.charCount(cp);
        }
        return b.toString();
    }

    static void show(String label, String s) {
        System.out.println("== " + label + " [" + cps(s) + "]");
        for (Locale l : LOCALES) {
            String up = s.toUpperCase(l), low = s.toLowerCase(l);
            System.out.println("  " + (l.toString().isEmpty() ? "ROOT" : l) + " upper [" + cps(up) + "]"
                + (up == s ? " same" : "") + " lower [" + cps(low) + "]" + (low == s ? " same" : ""));
        }
    }

    public static void main(String[] args) {
        show("empty", "");
        show("ascii mixed", "Hello, World iIjJ 123");
        show("already upper", "TITLE");
        show("already lower", "title");
        show("turkish i", "I i \u0130 \u0131 \u0130stanbul \u0131\u0131");
        show("I then dot", "I\u0307 i\u0307 \u0130\u0307");
        show("I dot past marks", "I\u0316\u0307 I\u0323\u0307 I\u0301\u0307 I\u0334\u0301\u0307 I\u0307\u0307");
        show("I alone and trailing", "I\u0316 I\u0316\u0316 I\u0334");
        show("dot without I", "A\u0307 \u0130\u0307 J\u0307 x\u0307");
        show("lithuanian lower", "I\u0300 J\u0301 \u012e\u0303 I\u0316\u0301 I\u0316 I\u0334\u0301 I\u0301a");
        show("lithuanian unconditional", "\u00cc \u00cd \u0128 \u00cca");
        show("lithuanian upper", "i\u0307 j\u0307 \u012f\u0307 \u0268\u0307 \u0456\u0307 \u0458\u0307 \u1e2d\u0307 \u1ecb\u0307");
        show("lithuanian upper past marks", "i\u0316\u0307 i\u0301\u0307 i\u0323\u0323\u0307 a\u0307 I\u0307 i\u0307\u0307");
        show("lithuanian i grave", "i\u0300 \u00ec \u00ed \u0129");
        show("sharp s", "Stra\u00dfe \u1e9e");
        show("one to many", "\u0149 \u01f0 \u0390 \u03b0 \u1f80 \u1f88 \u1fb3 \u1ff7 \u0587");
        show("ligatures", "\ufb00 \ufb01 \ufb02 \ufb03 \ufb04 \ufb05 \ufb06 \ufb13");
        show("final sigma", "\u03a3 A\u03a3 A\u03a3. \u0391\u03a3\u0391 \u039f\u0394\u03a5\u03a3\u03a3\u0395\u03a5\u03a3");
        show("final sigma and marks", "A\u03a3\u0301 A\u03a3 B \u0391'\u03a3 \u0391\u03a3'\u0391");
        show("sigma with dotted I", "I\u03a3 \u0130\u03a3 \u03a3I");
        show("greek", "\u0386\u03a3 \u03ac\u03c2 \u0390 \u03a9\u03c3");
        show("deseret", "\ud801\udc00 \ud801\udc28 \ud801\udc00\ud801\udc28");
        show("supplementary marks", "I\ud834\udd65\u0307 I\ud834\udd6d \ud834\udd85 i\ud834\udd6d\u0307 I\ud834\udd85");
        show("supplementary with I", "\ud801\udc00I\u0307 \ud83d\ude00i\u0307");
        show("lone surrogates", "\ud800 I\udc00\u0307 \ud800\u0307 i\ud800\u0307");
        show("title and dz", "\u01c4 \u01c5 \u01c6 \u1f88 \u2126 \u212a \u212b");
        show("no change cases", "123 .,; \u4e00\u4e8c \u00e9\u00c9");
        System.out.println(LOCALES[2].equals(new Locale("TR")) + " " + new Locale("TR", "tr", "Var"));
        softDotted();
        languages();
        identity();
        defaultLocale();
        nullLocale();
    }

    // The dot above survives until an earlier character has changed case; U+1D62 and U+2071 are
    // soft-dotted but have no upper case, which hides them from a probe without a cased prefix.
    static void softDotted() {
        String[] rows = {
            "ᵢ̇", "Aᵢ̇", "1ᵢ̇", "𐐀ᵢ̇",
            "\ud800ᵢ̇", "Ǆᵢ̇", "ᵢ̇ͅ",
            "ᵢ̇aᵢ̇", "aᵢ̇", "aⁱ̇", "iᵢ̇",
            "ßᵢ̇", "İᵢ̇", "ᵢ̇ͅ",
            "aⁱ̖̇", "aⁱ́̇", "ai̇̇",
        };
        Locale lt = new Locale("lt");
        for (String r : rows) {
            String up = r.toUpperCase(lt);
            System.out.println("soft " + cps(r) + " -> " + cps(up) + (up == r ? " same" : ""));
        }
        Locale tr = new Locale("tr");
        String[] lower = {"̖̇A", "̇A", "Ȧ", "İ̖", "̖̇I"};
        for (String r : lower) {
            System.out.println("tr lower " + cps(r) + " -> " + cps(r.toLowerCase(tr)));
        }
    }

    // Only the exact languages tr, az and lt have rules.
    static void languages() {
        String text = "Iiİı";
        for (String lang : new String[] {"th", "tk", "t", "tru", "a", "az", "l", "ltu", "AZ", "Lt", "xx"}) {
            Locale l = new Locale(lang);
            System.out.println("lang " + lang + " " + cps(text.toLowerCase(l)) + " | "
                + cps(text.toUpperCase(l)) + " | " + cps("ÍÌ".toLowerCase(l)));
        }
        System.out.println("fold " + cps(new Locale("İT").getLanguage()) + " | "
            + cps(new Locale("", "ßa").getCountry()) + " | "
            + cps(new Locale("Àb", "àc", "Àd").toString()));
    }

    static void identity() {
        String low = new String("title"), up = new String("TITLE");
        Locale tr = new Locale("tr");
        System.out.println("identity " + (low.toLowerCase(tr) == low) + " " + (up.toUpperCase(tr) == up)
            + " " + (low.toUpperCase(tr) == low) + " " + (up.toLowerCase(Locale.ROOT) == up)
            + " " + (low.toLowerCase() == low) + " " + (up.toUpperCase(new Locale("lt")) == up)
            + " " + ("\u0130".toUpperCase(tr) == "\u0130") + " " + ("\u0130".toUpperCase(Locale.ROOT) == "\u0130"));
    }

    // The no-argument methods, Formatter and the locale-free classes follow the default locale.
    static void defaultLocale() {
        Locale saved = Locale.getDefault();
        Locale.setDefault(new Locale("tr", "TR"));
        try {
            System.out.println("default tr: " + cps("Iii\u0130".toLowerCase()) + " | " + cps("Iii\u0130".toUpperCase())
                + " | " + cps(String.format("%S", "ii")));
            System.out.println("language " + new Locale("IT", "it").getLanguage() + " "
                + new Locale("ITALIAN", "tr").toString());
            URI a = URI.create("HTTP://WWW.ISIK.com/A");
            URI b = URI.create("http://www.isik.com/A");
            System.out.println("uri " + a.equals(b) + " " + (a.hashCode() == b.hashCode()));
            URI c = URI.create("FILE:/ISIK/A#I");
            URI d = URI.create("file:/ISIK/A#I");
            URI e = URI.create("file://ISIK.COM/ISIK");
            URI f = URI.create("FILE://isik.com/ISIK");
            System.out.println("uri scheme " + (c.hashCode() == d.hashCode()) + " " + (e.hashCode() == f.hashCode()));
            char bs = (char) 92;
            boolean windows = File.separatorChar == bs;
            // Only Windows ignores case in paths; elsewhere the same line is printed.
            System.out.println("file " + (!windows || new File("C:" + bs + "ISIK").hashCode()
                == new File("c:" + bs + "isik").hashCode()));
        } finally {
            Locale.setDefault(saved);
        }
        Locale.setDefault(new Locale("lt"));
        try {
            System.out.println("default lt: " + cps("I\u0301\u00cc".toLowerCase()) + " | " + cps("i\u0307".toUpperCase()));
        } finally {
            Locale.setDefault(saved);
        }
        System.out.println("restored: " + cps("Ii".toLowerCase()) + " | " + cps("Ii".toUpperCase()));
    }

    static void nullLocale() {
        try {
            "a".toUpperCase((Locale) null);
        } catch (NullPointerException e) {
            System.out.println("NPE upper");
        }
        try {
            "".toLowerCase((Locale) null);
        } catch (NullPointerException e) {
            System.out.println("NPE lower");
        }
    }
}
