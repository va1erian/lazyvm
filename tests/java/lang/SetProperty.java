package lang;

import java.security.InvalidParameterException;

/** System.setProperty and clearProperty, Math.random's range, InvalidParameterException. */
public class SetProperty {
    static void attempt(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + " ok");
        } catch (RuntimeException e) {
            System.out.println(what + " " + e);
        }
    }

    public static void main(String[] args) {
        System.out.println(System.setProperty("k", "v") + " " + System.setProperty("k", "w") + " "
                + System.getProperty("k"));
        System.out.println(System.clearProperty("k") + " " + System.clearProperty("k") + " "
                + System.getProperty("k"));
        attempt("set null key", new Runnable() { public void run() { System.setProperty(null, "v"); } });
        attempt("set empty key", new Runnable() { public void run() { System.setProperty("", "v"); } });
        attempt("set null value", new Runnable() { public void run() { System.setProperty("k", null); } });
        attempt("set empty value", new Runnable() { public void run() {
            System.out.println("[" + System.setProperty("k", "") + "]"); } });
        attempt("clear null", new Runnable() { public void run() { System.clearProperty(null); } });
        attempt("clear empty", new Runnable() { public void run() { System.clearProperty(""); } });
        System.out.println(Integer.getInteger("n", 5));
        System.setProperty("n", "0x20");
        System.out.println(Integer.getInteger("n", 5) + " " + Long.getLong("n"));
        System.setProperty("flag", "TRUE");
        System.out.println(Boolean.getBoolean("flag"));
        boolean inRange = true;
        for (int i = 0; i < 1000; i++) {
            double d = Math.random();
            inRange &= d >= 0.0 && d < 1.0;
        }
        System.out.println(inRange);
        try {
            throw new InvalidParameterException();
        } catch (IllegalArgumentException e) {
            System.out.println(e + " " + e.getMessage());
        }
        System.out.println(new InvalidParameterException("m"));
    }
}
