package util;

import java.util.StringJoiner;

/** StringJoiner: prefix, suffix, empty value, merge and length. */
public class Joiners {
    static void npe(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + ": no exception");
        } catch (NullPointerException e) {
            System.out.println(what + ": NPE " + e.getMessage());
        }
    }

    static void show(String label, StringJoiner j) {
        System.out.println(label + " [" + j + "] " + j.length());
    }

    public static void main(String[] args) {
        npe("delimiter", () -> new StringJoiner(null));
        npe("prefix", () -> new StringJoiner(",", null, "]"));
        npe("suffix", () -> new StringJoiner(",", "[", null));
        npe("empty", () -> new StringJoiner(",").setEmptyValue(null));
        npe("merge", () -> new StringJoiner(",").merge(null));

        StringJoiner plain = new StringJoiner(", ");
        show("empty plain", plain);
        plain.add("a").add("b").add("c");
        show("abc", plain);

        StringJoiner j = new StringJoiner(", ", "{", "}");
        show("empty braces", j);
        j.setEmptyValue("EMPTY");
        show("empty value", j);
        j.add("a");
        show("one", j);
        j.add(null).add("");
        show("null and empty", j);
        j.setEmptyValue("again");
        show("still full", j);

        StringJoiner one = new StringJoiner("/", "[", "]");
        one.add("");
        one.setEmptyValue("E");
        show("one empty element", one);

        StringJoiner k = new StringJoiner("-", "<", ">");
        k.merge(j);
        show("merge into empty", k);
        k.add("z");
        k.merge(j);
        show("merge into full", k);
        k.merge(new StringJoiner("+"));
        show("merge empty joiner", k);
        k.merge(k);
        show("merge self", k);

        StringJoiner bare = new StringJoiner("/");
        bare.merge(one);
        show("merge into bare", bare);
        StringJoiner other = new StringJoiner(";", "(", ")");
        other.add("x").add("y");
        bare.merge(other);
        show("merge different delimiters", bare);

        StringJoiner chars = new StringJoiner(new StringBuilder("|"), new StringBuffer("<"), "!!");
        chars.add(new StringBuilder("sb")).add(new StringBuffer("sf")).add("s");
        show("char sequences", chars);
        System.out.println(chars.toString().equals(chars.toString()));

        StringJoiner nums = new StringJoiner("", "", "");
        for (int i = 0; i < 5; i++) {
            nums.add(String.valueOf(i));
        }
        show("no delimiter", nums);
        System.out.println(String.join("-", "a", "b", "c"));
        System.out.println("done");
    }
}
