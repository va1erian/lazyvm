package util;

import java.util.logging.Level;

/** java.util.logging.Level: constants, parse, custom levels (docs/design/logging.md, section 2). */
public class LoggingLevels {
    interface Op {
        Object run() throws Exception;
    }

    static String t(Op op) {
        try {
            return String.valueOf(op.run());
        } catch (Exception e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    static void p(String label, Object value) {
        System.out.println(label + ": " + value);
    }

    static class Custom extends Level {
        Custom(String name, int value) {
            super(name, value);
        }

        Custom(String name, int value, String bundle) {
            super(name, value, bundle);
        }
    }

    public static void main(String[] args) {
        constants();
        parse();
        custom();
        equality();
    }

    static void constants() {
        Level[] all = {Level.OFF, Level.SEVERE, Level.WARNING, Level.INFO, Level.CONFIG, Level.FINE,
            Level.FINER, Level.FINEST, Level.ALL};
        for (Level l : all) {
            p("constant " + l.getName(), l + " " + l.intValue() + " " + l.getLocalizedName() + " "
                    + l.getResourceBundleName() + " " + l.hashCode());
        }
        p("distinct", Level.SEVERE != Level.WARNING);
        p("equals", Level.INFO.equals(Level.INFO) + " " + Level.INFO.equals(Level.WARNING) + " "
                + Level.INFO.equals(null) + " " + Level.INFO.equals("INFO"));
        p("toString is the name", Level.CONFIG.toString().equals(Level.CONFIG.getName()));
    }

    static void parse() {
        p("by name", Level.parse("INFO") == Level.INFO);
        p("every name", Level.parse("OFF") == Level.OFF && Level.parse("ALL") == Level.ALL
                && Level.parse("FINEST") == Level.FINEST && Level.parse("SEVERE") == Level.SEVERE);
        p("by number", Level.parse("800") == Level.INFO);
        p("by number min", Level.parse("-2147483648") == Level.ALL);
        p("by number max", Level.parse("2147483647") == Level.OFF);
        p("null", t(() -> Level.parse(null)));
        p("unknown", t(() -> Level.parse("bogus")));
        p("empty", t(() -> Level.parse("")));
        p("lower case", t(() -> Level.parse("info")));
        p("leading space", t(() -> Level.parse(" INFO")));
        p("trailing space", t(() -> Level.parse("INFO ")));
        p("hex", t(() -> Level.parse("0x10")));
        p("too big", t(() -> Level.parse("2147483648")));
        p("float", t(() -> Level.parse("1.5")));
        Level n = Level.parse("801");
        p("new number", n + " " + n.intValue() + " " + n.getName() + " " + n.getClass().getName());
        p("cached", Level.parse("801") == n);
        Level neg = Level.parse("-5");
        p("negative", neg + " " + neg.intValue());
        Level plus = Level.parse("+5");
        p("plus", plus + " " + plus.intValue() + " " + (plus == Level.parse("+5")) + " " + (plus == Level.parse("5")));
        p("zero padded", Level.parse("0800") == Level.INFO);
        p("bundle of numeric", n.getResourceBundleName());
    }

    static void custom() {
        Level a = new Custom("ALPHA", 850);
        p("fields", a + " " + a.intValue() + " " + a.getName() + " " + a.getLocalizedName() + " "
                + a.getResourceBundleName() + " " + a.hashCode());
        p("parse custom name", Level.parse("ALPHA") == a);
        p("parse custom number", Level.parse("850") == a);
        Level a2 = new Custom("ALPHA", 851);
        p("same name, first wins", (Level.parse("ALPHA") == a) + " " + (Level.parse("ALPHA") == a2));
        p("second by number", Level.parse("851") == a2);
        Level b = new Custom("BETA", 850);
        p("same number", (Level.parse("850") == a) + " " + (Level.parse("BETA") == b));
        Level c = new Custom("Gamma Level", 852, "no.such.bundle");
        p("bundle name", c.getResourceBundleName() + " " + c.getName() + " " + c.getLocalizedName());
        p("null name", t(() -> new Custom(null, 5)));
        p("mixed case", new Custom("MiXeD", 853) + " " + (Level.parse("MiXeD") != null));
        p("name not found lower", t(() -> Level.parse("mixed")));
        p("empty name", new Custom("", 854).getName().length());
        p("negative value", new Custom("NEG", -7).intValue());
    }

    static void equality() {
        Level x = new Custom("EQ1", 860);
        Level y = new Custom("EQ2", 860);
        p("equal by value", x.equals(y) + " " + y.equals(x) + " " + (x.hashCode() == y.hashCode()));
        p("custom vs info", new Custom("EQ3", 800).equals(Level.INFO) + " " + Level.INFO.equals(new Custom("EQ4", 800)));
        p("not equal", x.equals(Level.INFO));
        p("hash is value", x.hashCode());
    }
}
