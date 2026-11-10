package util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.MissingResourceException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

/** java.util.logging: the logger tree, level inheritance, LogManager (docs/design/logging.md, section 1 and 2). */
public class Logging {
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

    static String name(Logger l) {
        return l == null ? "<none>" : "[" + l.getName() + "]";
    }

    public static void main(String[] args) throws Exception {
        root();
        tree();
        inheritance();
        isLoggable();
        manager();
        handlerList();
        nulls();
        bundles();
    }

    static void root() {
        Logger root = Logger.getLogger("");
        p("root name", name(root));
        p("root level", root.getLevel());
        p("root parent", root.getParent());
        p("root same", root == Logger.getLogger("") && root == LogManager.getLogManager().getLogger(""));
        p("root useParent", root.getUseParentHandlers());
        p("root filter", root.getFilter());
        Handler[] hs = root.getHandlers();
        p("root handlers", hs.length);
        p("root handler class", hs[0].getClass().getName());
        p("root handler level", hs[0].getLevel());
        p("root handler formatter", hs[0].getFormatter().getClass().getName());
        p("global", name(Logger.getGlobal()) + " " + Logger.GLOBAL_LOGGER_NAME);
        p("global same", Logger.getGlobal() == Logger.getLogger("global") && Logger.getGlobal() == Logger.global);
        p("global parent", name(Logger.getGlobal().getParent()));
        p("global level", Logger.getGlobal().getLevel());
        p("plain logger", name(Logger.getLogger("plain")) + " " + name(Logger.getLogger("plain").getParent())
                + " " + Logger.getLogger("plain").getLevel() + " " + Logger.getLogger("plain").getHandlers().length);
        p("same instance", Logger.getLogger("plain") == Logger.getLogger("plain"));
    }

    static void tree() {
        Logger abc = Logger.getLogger("t.a.b.c");
        p("t.a.b.c parent", name(abc.getParent()));
        Logger ta = Logger.getLogger("t.a");
        p("after t.a", name(abc.getParent()) + " " + name(ta.getParent()));
        Logger tab = Logger.getLogger("t.a.b");
        p("after t.a.b", name(abc.getParent()) + " " + name(tab.getParent()) + " " + name(ta.getParent()));
        Logger t = Logger.getLogger("t");
        p("after t", name(ta.getParent()) + " " + name(t.getParent()));
        Logger q = Logger.getLogger("q.r");
        Logger q1 = Logger.getLogger("q");
        p("q reparent", name(q.getParent()) + " " + name(q1.getParent()));
        q.setParent(Logger.getLogger("other"));
        p("setParent", name(q.getParent()));
        p("new child of re-parented", name(Logger.getLogger("q.r.s").getParent()));
        p("skip levels", name(Logger.getLogger("sk.x.y.z").getParent()));
        Logger an = Logger.getAnonymousLogger();
        p("anonymous", an.getName() + " " + name(an.getParent()) + " " + an.getLevel() + " " + an.getHandlers().length);
        p("anonymous distinct", Logger.getAnonymousLogger() != Logger.getAnonymousLogger());
        an.setParent(ta);
        p("anonymous setParent", name(an.getParent()));
        p("getLogger name", Logger.getLogger("Mixed.Case").getName());
        p("case sensitive", Logger.getLogger("mixed.case") != Logger.getLogger("Mixed.Case"));
    }

    static void inheritance() {
        Logger ta = Logger.getLogger("t.a");
        Logger abc = Logger.getLogger("t.a.b.c");
        Logger tab = Logger.getLogger("t.a.b");
        p("inherit default", abc.getLevel() + " " + abc.isLoggable(Level.INFO) + " " + abc.isLoggable(Level.CONFIG));
        ta.setLevel(Level.FINE);
        p("ta FINE", abc.getLevel() + " " + abc.isLoggable(Level.FINE) + " " + abc.isLoggable(Level.FINER));
        tab.setLevel(Level.WARNING);
        p("tab WARNING", abc.isLoggable(Level.FINE) + " " + abc.isLoggable(Level.WARNING) + " " + ta.isLoggable(Level.FINE));
        tab.setLevel(null);
        p("tab null", tab.getLevel() + " " + abc.isLoggable(Level.FINE));
        ta.setLevel(null);
        p("ta null", abc.isLoggable(Level.FINE) + " " + abc.isLoggable(Level.INFO));
        Logger root = Logger.getLogger("");
        Logger leaf = Logger.getLogger("lvl.leaf");
        root.setLevel(Level.SEVERE);
        p("root SEVERE", leaf.isLoggable(Level.WARNING) + " " + leaf.isLoggable(Level.SEVERE));
        root.setLevel(Level.ALL);
        p("root ALL", leaf.isLoggable(Level.FINEST));
        root.setLevel(Level.INFO);
        Logger anon = Logger.getAnonymousLogger();
        p("anonymous inherits root", anon.isLoggable(Level.INFO) + " " + anon.isLoggable(Level.FINE));
        Logger lone = new Logger("lone", null) {};
        p("no parent, no level", lone.getLevel() + " " + lone.isLoggable(Level.SEVERE) + " " + lone.isLoggable(Level.FINEST));
    }

    static void isLoggable() {
        Logger l = Logger.getLogger("lv");
        l.setLevel(Level.OFF);
        p("OFF", l.isLoggable(Level.SEVERE) + " " + l.isLoggable(Level.OFF) + " " + l.isLoggable(Level.ALL));
        l.setLevel(Level.ALL);
        p("ALL", l.isLoggable(Level.ALL) + " " + l.isLoggable(Level.OFF) + " " + l.isLoggable(Level.FINEST));
        l.setLevel(Level.CONFIG);
        p("CONFIG", l.isLoggable(Level.CONFIG) + " " + l.isLoggable(Level.FINE) + " " + l.isLoggable(Level.OFF));
        l.setLevel(Level.parse("750"));
        p("750", l.isLoggable(Level.parse("749")) + " " + l.isLoggable(Level.parse("750")) + " " + l.isLoggable(Level.INFO));
        p("isLoggable null", t(() -> l.isLoggable(null)));
        p("getLevel", l.getLevel() == Level.parse("750"));
    }

    static void manager() {
        LogManager m = LogManager.getLogManager();
        p("same manager", m == LogManager.getLogManager());
        Logger ta = Logger.getLogger("t.a");
        p("getLogger", m.getLogger("t.a") == ta);
        p("getLogger unknown", m.getLogger("never.created"));
        p("getLogger null", t(() -> m.getLogger(null)));
        List<String> names = Collections.list(m.getLoggerNames());
        p("names has", names.contains("") + " " + names.contains("global") + " " + names.contains("t.a.b.c"));
        p("names has anonymous", names.contains(null));
        Logger custom = new Logger("custom.name", null) {};
        p("custom before", m.getLogger("custom.name") + " " + custom.getParent());
        p("addLogger", m.addLogger(custom));
        p("custom after", (m.getLogger("custom.name") == custom) + " " + name(custom.getParent()));
        p("getLogger finds custom", Logger.getLogger("custom.name") == custom);
        p("addLogger twice", m.addLogger(custom));
        p("addLogger same name", m.addLogger(new Logger("custom.name", null) {}));
        p("addLogger kept first", m.getLogger("custom.name") == custom);
        Logger child = Logger.getLogger("custom.name.kid");
        p("child of custom", name(child.getParent()));
        resetManager(m);
    }

    static void resetManager(LogManager m) {
        Logger root = Logger.getLogger("");
        Logger rr = Logger.getLogger("rs");
        int[] closed = {0};
        Handler h = new Handler() {
            public void publish(LogRecord r) {}

            public void flush() {}

            public void close() {
                closed[0]++;
            }
        };
        rr.setLevel(Level.FINE);
        rr.addHandler(h);
        rr.setUseParentHandlers(false);
        root.setLevel(Level.SEVERE);
        root.addHandler(h);
        m.reset();
        p("reset closes handlers", closed[0]);
        p("reset root", root.getHandlers().length + " " + root.getLevel());
        p("reset child", rr.getLevel() + " " + rr.getHandlers().length + " " + rr.getUseParentHandlers());
        p("reset keeps registry", Logger.getLogger("rs") == rr);
        root.addHandler(new ConsoleHandler());
        p("after reset add", root.getHandlers().length);
        root.removeHandler(root.getHandlers()[0]);
    }

    static void handlerList() {
        Logger l = Logger.getLogger("hl");
        Handler a = new StreamHandler();
        Handler b = new StreamHandler();
        l.addHandler(a);
        l.addHandler(b);
        l.addHandler(a);
        Handler[] hs = l.getHandlers();
        p("order", hs.length + " " + (hs[0] == a) + " " + (hs[1] == b) + " " + (hs[2] == a));
        hs[0] = null;
        p("copy", l.getHandlers()[0] == a);
        l.removeHandler(a);
        hs = l.getHandlers();
        p("remove one", hs.length + " " + (hs[0] == b) + " " + (hs[1] == a));
        l.removeHandler(new StreamHandler());
        p("remove absent", l.getHandlers().length);
        l.removeHandler(null);
        p("remove null", l.getHandlers().length);
        l.removeHandler(a);
        l.removeHandler(b);
        p("empty", l.getHandlers().length);
        p("useParent", l.getUseParentHandlers());
        l.setUseParentHandlers(false);
        p("useParent off", l.getUseParentHandlers());
        p("filter", l.getFilter());
        l.setFilter(r -> true);
        p("filter set", l.getFilter() != null);
        l.setFilter(null);
        p("filter cleared", l.getFilter());
    }

    static void nulls() {
        Logger l = Logger.getLogger("nl");
        p("getLogger null", t(() -> Logger.getLogger(null)));
        p("setParent null", t(() -> {
            l.setParent(null);
            return "ok";
        }));
        p("addHandler null", t(() -> {
            l.addHandler(null);
            return "ok";
        }));
        p("setLevel null", t(() -> {
            l.setLevel(null);
            return "ok";
        }));
        p("log null level", t(() -> {
            l.log(null, "x");
            return "ok";
        }));
        p("log null record", t(() -> {
            l.log((LogRecord) null);
            return "ok";
        }));
        p("logp null level", t(() -> {
            l.logp(null, "c", "m", "x");
            return "ok";
        }));
        p("addLogger null", t(() -> LogManager.getLogManager().addLogger(null)));
        p("handler formatter", new StreamHandler().getFormatter().getClass().getName() + " "
                + (new StreamHandler().getFormatter() instanceof SimpleFormatter));
    }

    static void bundles() {
        p("bundle name", Logger.getLogger("bn").getResourceBundleName());
        p("getLogger bundle null", Logger.getLogger("bn2", null).getName());
        p("getLogger bundle missing", t(() -> Logger.getLogger("bn3", "no.such.Bundle")));
        p("anonymous bundle missing", t(() -> Logger.getAnonymousLogger("no.such.Bundle")));
        p("ctor bundle missing", t(() -> new Logger("bn4", "no.such.Bundle") {}));
        try {
            new Logger("bn5", "no.such.Bundle") {};
        } catch (MissingResourceException e) {
            p("missing class and key", e.getClassName() + " / " + e.getKey());
        }
        p("not registered after failure", LogManager.getLogManager().getLogger("bn3"));
        p("same name later", Logger.getLogger("bn3").getName());
        List<String> none = new ArrayList<>();
        p("done", none.size());
    }
}
