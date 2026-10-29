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
        boolean has = System.getenv("PATH") != null;
        System.out.println(has == (m.get("PATH") != null));
        System.out.println(has == m.containsKey("PATH"));
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
        boolean same = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            same &= e.getValue().equals(System.getenv(e.getKey()));
        }
        System.out.println(same);
    }
}
