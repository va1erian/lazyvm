package thread;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Library classes first used at the bottom of an overflowing stack, where they
 * cannot be initialized, work from then on: the shortage passes. Each frame on
 * the way back up tries again. No line says how deep it went, which differs.
 */
public class StackOverflowFirstUse {
    static int tried;
    static Map<String, Integer> map;
    static String text;
    static boolean matched;

    /** The first use of `System.out` in this program, with no room for it. */
    static void printing() {
        try {
            printing();
        } catch (StackOverflowError e) {
            if (tried++ == 0) {
                System.out.println("printed with no room");
            }
            throw e;
        }
    }

    static void concatenating(int depth) {
        try {
            concatenating(depth + 1);
        } catch (StackOverflowError e) {
            text = "depth " + depth;
            throw e;
        }
    }

    static void mapping(int depth) {
        try {
            mapping(depth + 1);
        } catch (StackOverflowError e) {
            if (map == null) {
                map = new TreeMap<String, Integer>();
            }
            map.put("k" + depth, depth);
            throw e;
        }
    }

    static void formatting(int depth) {
        try {
            formatting(depth + 1);
        } catch (StackOverflowError e) {
            text = String.format("%s %d", Arrays.asList(1, 2), depth);
            throw e;
        }
    }

    static void matching(int depth) {
        try {
            matching(depth + 1);
        } catch (StackOverflowError e) {
            matched = Pattern.matches("a+", "aaa");
            throw e;
        }
    }

    static void plainRecursion() {
        plainRecursion();
    }

    interface Body {
        void run();
    }

    static void run(String name, Body body) {
        try {
            body.run();
            System.out.println(name + " returned");
        } catch (StackOverflowError e) {
            System.out.println(name + " overflowed, caught in main");
        }
    }

    public static void main(String[] args) throws Exception {
        run("println", StackOverflowFirstUse::printing);
        System.out.println("println works, tried at the bottom: " + (tried > 0));
        run("concat", () -> concatenating(0));
        System.out.println("concat works: " + text.startsWith("depth 0"));
        run("TreeMap", () -> mapping(0));
        System.out.println("TreeMap works: " + map.containsKey("k0"));
        run("format", () -> formatting(0));
        System.out.println("format works: " + String.format("%s %d", new ArrayList<String>(), 1));
        run("regex", () -> matching(0));
        System.out.println("regex works: " + matched);

        Thread t = new Thread(() -> run("on a thread", () -> formatting(0)));
        t.start();
        t.join();
        run("plain", StackOverflowFirstUse::plainRecursion);
    }
}
