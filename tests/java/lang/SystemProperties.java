// VMARGS: -Dfrom.line=1
package lang;

import java.util.Properties;
import java.util.TreeSet;

/** System.getProperties and setProperties, and their tie to getProperty. */
public class SystemProperties {
    static void attempt(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + " ok");
        } catch (RuntimeException e) {
            System.out.println(what + " " + e);
        }
    }

    /** Set on every VM by the Javadoc of getProperties; values differ, so only whether they are set. */
    static final String[] STANDARD = {
        "java.version", "java.vendor", "java.vendor.url", "java.home",
        "java.vm.specification.version", "java.vm.specification.vendor",
        "java.vm.specification.name", "java.vm.version", "java.vm.vendor", "java.vm.name",
        "java.specification.version", "java.specification.vendor", "java.specification.name",
        "java.class.version", "java.class.path", "java.library.path", "java.io.tmpdir",
        "java.ext.dirs", "os.name", "os.arch", "os.version",
        "file.separator", "path.separator", "line.separator",
        "user.name", "user.home", "user.dir",
    };

    static void standard(Properties p) {
        TreeSet<String> missing = new TreeSet<String>();
        for (String key : STANDARD) {
            if (p.getProperty(key) == null || !p.getProperty(key).equals(System.getProperty(key))) {
                missing.add(key);
            }
        }
        System.out.println("missing " + missing);
    }

    public static void main(String[] args) {
        // Before anything asks for the table.
        System.out.println("line " + System.getProperty("from.line"));
        System.out.println("first set " + System.setProperty("first", "1"));
        Properties p = System.getProperties();
        System.out.println("class " + p.getClass().getName() + ", same " + (p == System.getProperties()));
        System.out.println("first " + p.getProperty("first") + ", line " + p.getProperty("from.line"));
        standard(p);
        for (String key : p.stringPropertyNames()) {
            if (System.getProperty(key) == null && p.get(key) instanceof String) {
                System.out.println("not found through System: " + key);
            }
        }

        // Each way in shows the other.
        p.setProperty("a", "1");
        System.out.println("a " + System.getProperty("a") + " " + System.getProperty("a", "def"));
        System.out.println("b set " + System.setProperty("b", "2") + ", in table " + p.getProperty("b"));
        System.out.println("b reset " + System.setProperty("b", "3") + ", in table " + p.get("b"));
        System.out.println("a cleared " + System.clearProperty("a") + ", in table " + p.get("a"));
        p.remove("b");
        System.out.println("b removed " + System.getProperty("b") + " " + System.getProperty("b", "def"));

        // Lone surrogates keep names apart in the table too.
        System.setProperty("s\uD800", "one");
        System.setProperty("s\uDBFF", "two");
        System.out.println(System.getProperty("s\uD800") + " " + System.getProperty("s\uDBFF"));

        // Values that are not Strings stay in the table and fail the casts.
        p.put("object", Integer.valueOf(7));
        System.out.println("object " + System.getProperty("object") + " " + System.getProperty("object", "def"));
        attempt("set over object", new Runnable() {
            @Override
            public void run() {
                System.setProperty("object", "s");
            }
        });
        System.out.println("object now " + p.get("object"));
        p.put("object2", Integer.valueOf(8));
        attempt("clear object", new Runnable() {
            @Override
            public void run() {
                System.clearProperty("object2");
            }
        });
        System.out.println("object2 now " + p.get("object2"));

        // A table of the program's own, with the old one as defaults.
        Properties mine = new Properties(p);
        mine.setProperty("c", "3");
        p.setProperty("d", "4");
        System.setProperties(mine);
        System.out.println("own table " + (System.getProperties() == mine) + ", " + System.getProperty("c")
                + " " + System.getProperty("d") + " " + System.getProperty("java.version").isEmpty());
        System.out.println("clear from defaults " + System.clearProperty("d") + ", still " + System.getProperty("d"));
        System.out.println("set in own " + System.setProperty("d", "5") + ", old table " + p.getProperty("d"));

        // Null brings back what the VM started with, -D included, and not what was set since.
        System.setProperties(null);
        Properties back = System.getProperties();
        System.out.println("back " + (back == mine) + " " + (back == p) + " " + back.getClass().getName());
        System.out.println("gone " + back.getProperty("first") + " " + back.getProperty("c")
                + " " + System.getProperty("first"));
        System.out.println("line again " + back.getProperty("from.line"));
        standard(back);

        // lineSeparator is fixed at the start, whatever the property says later.
        String separator = System.lineSeparator();
        System.setProperty("line.separator", "changed");
        System.out.println("separator kept " + separator.equals(System.lineSeparator())
                + ", property " + System.getProperty("line.separator"));

        attempt("get null", new Runnable() {
            @Override
            public void run() {
                System.getProperty(null);
            }
        });
        attempt("table get null", new Runnable() {
            @Override
            public void run() {
                System.getProperties().get(null);
            }
        });

        System.runFinalization();
        Runtime.getRuntime().runFinalization();
        System.out.println("finalization returned");
    }
}
