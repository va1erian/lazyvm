package util;

import java.util.Locale;

/** Locale constructors, legacy codes, toString, equality, extensions and the default locale. */
public class LocaleBasics {
    static void p(Locale l) {
        System.out.println("[" + l + "] L=" + l.getLanguage() + " S=" + l.getScript() + " C=" + l.getCountry()
                + " V=" + l.getVariant() + " tag=" + l.toLanguageTag() + " ek=" + l.getExtensionKeys()
                + " rt=" + l.equals(Locale.forLanguageTag(l.toLanguageTag())) + " hc=" + l.hashCode());
    }

    static void tryRun(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + " ok");
        } catch (RuntimeException e) {
            System.out.println(what + " " + e);
        }
    }

    public static void main(String[] args) {
        p(new Locale("ja", "JP", "JP")); p(new Locale("th", "TH", "TH")); p(new Locale("no", "NO", "NY"));
        p(new Locale("no", "NO")); p(new Locale("no")); p(new Locale("he", "IL")); p(new Locale("id"));
        p(new Locale("yi")); p(new Locale("HE")); p(new Locale("iw", "il", "x"));
        p(new Locale("en", "US", "a_b")); p(new Locale("en", "US", "a b")); p(new Locale("en", "US", "a-b"));
        p(new Locale("en", "", "_x")); p(new Locale("en", "US", "POSIX_WIN")); p(new Locale("english"));
        p(new Locale("e")); p(new Locale("en", "USA")); p(new Locale("en", "U")); p(new Locale("en", "123"));
        p(new Locale("123")); p(new Locale("en", "US", "1234")); p(new Locale("en", "US", "abcd"));
        p(new Locale("en", "US", "ab")); p(new Locale("", "US")); p(new Locale("", "", "foo"));
        p(new Locale("", "", "abcde")); p(new Locale("EN", "us", "Posix")); p(new Locale("en", "US", "  "));
        p(new Locale("en", "US", "_")); p(new Locale("en", "US", "a__b")); p(new Locale("en", "US", "_a_"));
        p(new Locale("ja", "JP", "JP_X")); p(new Locale("en", "US", "lvariant"));
        p(Locale.ROOT); p(Locale.JAPAN); p(Locale.CHINA); p(Locale.ENGLISH);
        System.out.println(new Locale("en", "US").equals(Locale.forLanguageTag("en-US")) + " "
                + Locale.forLanguageTag("en-Latn").equals(Locale.ENGLISH) + " "
                + Locale.forLanguageTag("en-US-u-ca-gregory").equals(Locale.US));
        final Locale l = Locale.forLanguageTag("en-u-attr-ca-gregory-nu-latn");
        System.out.println(l.getUnicodeLocaleType("ca") + " " + l.getUnicodeLocaleType("nu") + " "
                + l.getUnicodeLocaleType("zz") + " " + l.getUnicodeLocaleKeys() + " "
                + l.getUnicodeLocaleAttributes() + " " + l.getExtension('u') + " " + l.getExtension('a'));
        System.out.println(Locale.forLanguageTag("en-u-ca").getUnicodeLocaleType("ca") + "|"
                + Locale.forLanguageTag("en-u-ca-gregory").getUnicodeLocaleType("CA"));
        tryRun("getExtension(1)", new Runnable() { public void run() { l.getExtension('1'); } });
        tryRun("getUnicodeLocaleType(null)", new Runnable() { public void run() { l.getUnicodeLocaleType(null); } });
        tryRun("getUnicodeLocaleType(c)", new Runnable() { public void run() { l.getUnicodeLocaleType("c"); } });
        tryRun("new Locale(null)", new Runnable() { public void run() { new Locale(null); } });
        tryRun("new Locale(en,null)", new Runnable() { public void run() { new Locale("en", null); } });
        tryRun("getDefault(null)", new Runnable() { public void run() { Locale.getDefault(null); } });
        tryRun("setDefault(null)", new Runnable() { public void run() { Locale.setDefault(null); } });
        tryRun("setDefault(FORMAT,null)", new Runnable() { public void run() {
            Locale.setDefault(Locale.Category.FORMAT, null); } });
        tryRun("setDefault(null,US)", new Runnable() { public void run() { Locale.setDefault(null, Locale.US); } });

        System.out.println("default " + Locale.getDefault() + " " + Locale.getDefault(Locale.Category.FORMAT)
                + " " + Locale.getDefault(Locale.Category.DISPLAY));
        Locale.setDefault(Locale.Category.FORMAT, Locale.FRANCE);
        System.out.println(Locale.getDefault() + " " + Locale.getDefault(Locale.Category.FORMAT) + " "
                + Locale.getDefault(Locale.Category.DISPLAY));
        Locale.setDefault(Locale.GERMANY);
        System.out.println(Locale.getDefault() + " " + Locale.getDefault(Locale.Category.FORMAT) + " "
                + Locale.getDefault(Locale.Category.DISPLAY));
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ITALY);
        System.out.println(Locale.getDefault() + " " + Locale.getDefault(Locale.Category.FORMAT) + " "
                + Locale.getDefault(Locale.Category.DISPLAY));
    }
}
