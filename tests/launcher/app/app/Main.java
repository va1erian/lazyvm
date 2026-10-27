package app;

/** The -jar entry point: uses a class from lib.jar and prints its arguments. */
public class Main {
    public static void main(String[] args) {
        System.out.println(util.Helper.greet("jar"));
        System.out.println("args=" + args.length);
        for (String a : args) {
            System.out.println("[" + a + "]");
        }
        try {
            Class.forName("java.lang.Evil");
            System.out.println("evil loaded");
        } catch (Throwable t) {
            System.out.println("evil: " + t);
        }
    }
}
