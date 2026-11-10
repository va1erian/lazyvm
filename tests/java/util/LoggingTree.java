package util;

import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Where a logger hangs in the tree when ancestors are registered later, and the lookup rules of
 * Level.parse for names that look like numbers (docs/design/logging.md, sections 1 and 2).
 */
public class LoggingTree {
    static class Lv extends Level {
        Lv(String name, int value) {
            super(name, value);
        }

        public String getLocalizedName() {
            return "localized " + getName();
        }

        public String getName() {
            return "named " + super.getName();
        }
    }

    static void p(String label, Object value) {
        System.out.println(label + ": " + value);
    }

    static String name(Logger l) {
        return l == null ? "<none>" : "[" + l.getName() + "]";
    }

    public static void main(String[] args) {
        cuts();
        explicitParents();
        scale();
        levels();
    }

    static void cuts() {
        Logger deep = Logger.getLogger("c.a.b.c.d.e.f");
        p("only the root is registered", name(deep.getParent()));
        Logger a = Logger.getLogger("c");
        p("c registered", name(deep.getParent()) + " " + name(a.getParent()));
        Logger mid = Logger.getLogger("c.a.b.c");
        p("c.a.b.c registered", name(deep.getParent()) + " " + name(mid.getParent()));
        Logger near = Logger.getLogger("c.a.b.c.d.e");
        p("c.a.b.c.d.e registered", name(deep.getParent()) + " " + name(near.getParent()));
        Logger sibling = Logger.getLogger("c.a.b.cd");
        p("a name that only shares letters", name(sibling.getParent()));
    }

    static void explicitParents() {
        Logger root = Logger.getLogger("");
        Logger c1 = Logger.getLogger("e1.a.b.c");
        c1.setParent(root);
        Logger.getLogger("e1.a.b");
        p("explicit root, nearer registered", name(c1.getParent()));
        Logger c2 = Logger.getLogger("e2.a.b.c");
        c2.setParent(Logger.getLogger("e2x"));
        Logger.getLogger("e2.a.b");
        p("explicit unrelated, nearer registered", name(c2.getParent()));
        Logger c3 = Logger.getLogger("e3.a.b.c");
        Logger e3 = Logger.getLogger("e3");
        c3.setParent(e3);
        Logger.getLogger("e3.a.b");
        p("explicit far ancestor, nearer registered", name(c3.getParent()));
        Logger anon = Logger.getAnonymousLogger();
        Logger c5 = Logger.getLogger("e5.a.b");
        c5.setParent(anon);
        Logger.getLogger("e5.a");
        p("explicit anonymous, ancestor registered", name(c5.getParent()));
        Logger c6 = Logger.getLogger("e6.a.b.c.d");
        Logger e6b = Logger.getLogger("e6.a.b");
        Logger.getLogger("e6");
        p("an even nearer ancestor keeps its child", name(c6.getParent()) + " " + name(e6b.getParent()));
        Logger back = Logger.getLogger("e7.x.y");
        back.setParent(Logger.getLogger("e7other"));
        p("explicit parent, no ancestor registered", name(back.getParent()));
    }

    static void scale() {
        for (int i = 0; i < 1500; i++) {
            Logger.getLogger("s.n" + i);
        }
        for (int i = 0; i < 1500; i++) {
            Logger.getLogger("s.n" + i + ".child");
        }
        Logger.getLogger("s");
        p("many loggers, ancestor last", name(Logger.getLogger("s.n7").getParent()) + " "
                + name(Logger.getLogger("s.n7.child").getParent()));
        int count = 0;
        for (int i = 0; i < 1500; i++) {
            count += Level.parse("" + (100000 + i)).intValue() == 100000 + i ? 1 : 0;
        }
        p("many levels", count);
        p("names", LogManager.getLogManager().getLogger("s.n1499.child") != null);
    }

    static void levels() {
        p("parse by sign and zeros", Level.parse("800") + " " + Level.parse("+800") + " " + Level.parse("0800"));
        p("parse zero", Level.parse("-0").getName() + " " + Level.parse("0").getName());
        Level n = new Lv("77", 78) {};
        p("name first for numeric names", (Level.parse("77") == n) + " " + Level.parse("78").getName());
        Level v = new Lv("42x", 42) {};
        p("value lookup", Level.parse("42") == v);
        p("parse unicode digits", Level.parse("١٢").intValue());
        p("OFF and ALL by value", (Level.parse("2147483647") == Level.OFF) + " " + (Level.parse("-2147483648") == Level.ALL));
    }
}
