package app;

/** For each argument: its length, first char, and the system property of that name. */
public class Props {
    public static void main(String[] args) {
        for (String a : args) {
            System.out.println(a.length() + " " + (int) a.charAt(a.length() - 1) + " " + System.getProperty(a));
        }
    }
}
