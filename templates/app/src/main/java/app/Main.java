package app;

/** The template app: replace with your own. */
public class Main {
    public static void main(String[] args) {
        StringBuilder who = new StringBuilder();
        for (String a : args) {
            who.append(' ').append(a);
        }
        System.out.println("Hello from LazyVM" + (who.length() > 0 ? "," + who : "") + "!");
    }
}
