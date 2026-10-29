package app;

/** For each argument: its length, its last UTF-16 unit (-1 if empty) and the system property of that name. */
public class Props {
    public static void main(String[] args) {
        for (String a : args) {
            int last = a.length() == 0 ? -1 : a.charAt(a.length() - 1);
            System.out.println(a.length() + " " + last + " " + System.getProperty(a.length() == 0 ? "x" : a));
        }
    }
}
