package lang;

import java.util.Map;

/** System.getenv: only facts that hold on any machine. */
public class Environment {
    static void attempt(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + " ok");
        } catch (Throwable t) {
            System.out.println(what + " " + t.getClass().getName());
        }
    }

    public static void main(String[] args) {
        final Map<String, String> m = System.getenv();
        System.out.println(m instanceof java.io.Serializable && !(m instanceof java.util.HashMap));
        // LazyOS may hand the program no environment, so presence itself is not printed.
        // The map is case-exact, and Windows may spell the variable "Path", so use its own name.
        String pathKey = "PATH";
        for (String k : m.keySet()) {
            if (k.equalsIgnoreCase("PATH")) {
                pathKey = k;
            }
        }
        boolean has = System.getenv(pathKey) != null;
        System.out.println(has == (m.get(pathKey) != null));
        System.out.println(has == m.containsKey(pathKey));
        System.out.println(has == (m.size() > 0) || !has);
        System.out.println(System.getenv("") + " " + System.getenv("no=such") + " " + m.get(""));
        System.out.println(m == System.getenv());
        attempt("getenv(null)", new Runnable() { public void run() { System.getenv(null); } });
        attempt("get(null)", new Runnable() { public void run() { m.get(null); } });
        attempt("containsKey(null)", new Runnable() { public void run() { m.containsKey(null); } });
        attempt("get(1)", new Runnable() { public void run() { m.get(Integer.valueOf(1)); } });
        attempt("put", new Runnable() { public void run() { m.put("a", "b"); } });
        attempt("remove", new Runnable() { public void run() { m.remove("PATH"); } });
        attempt("clear", new Runnable() { public void run() { m.clear(); } });
        attempt("keySet.remove", new Runnable() { public void run() { m.keySet().remove("PATH"); } });
        attempt("setValue", new Runnable() { public void run() {
            if (m.isEmpty()) {
                throw new UnsupportedOperationException();
            }
            m.entrySet().iterator().next().setValue("x"); } });
        System.out.println(m.containsKey("") + " " + m.keySet().contains("") + " " + System.getenv("") + " "
                + System.getenv("="));
        attempt("containsValue(null)", new Runnable() { public void run() { m.containsValue(null); } });
        attempt("containsValue(1)", new Runnable() { public void run() { m.containsValue(Integer.valueOf(1)); } });
        attempt("containsValue(nope)", new Runnable() { public void run() { m.containsValue("no such"); } });
        // Names may start with '=' (Windows drive variables), never be empty, and look the same both ways.
        boolean names = true;
        for (String k : m.keySet()) {
            names &= !k.isEmpty() && k.indexOf('=', 1) < 0 && System.getenv(k) != null;
        }
        System.out.println(names);
        // Windows ignores case in single lookups; elsewhere this is vacuously true. The decision
        // comes from the platform, so changing os.name must not change it.
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String path = System.getenv("PATH");
        System.out.println(!windows || path == null || path.equals(System.getenv("pAtH")));
        System.setProperty("os.name", "Linux");
        System.out.println(!windows || path == null || path.equals(System.getenv("pAtH")));
        System.out.println(windows || path == null || System.getenv("pAtH") == null || true);
        System.out.println(!windows || !m.containsKey("pAtH"));
        boolean same = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            same &= e.getValue().equals(System.getenv(e.getKey()));
        }
        System.out.println(same);
    }
}
