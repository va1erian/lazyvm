package app;

import java.io.InputStream;

/** Prints what a launch sees: the class path property, a resource and a library class. */
public class Probe {
    public static void main(String[] args) throws Exception {
        System.out.println("cp=" + System.getProperty("java.class.path"));
        InputStream in = ClassLoader.getSystemResourceAsStream("r.txt");
        if (in == null) {
            System.out.println("r.txt: none");
        } else {
            byte[] b = new byte[64];
            int n = in.read(b);
            System.out.println("r.txt: " + new String(b, 0, Math.max(n, 0)).trim());
        }
        System.out.println(util.Helper.greet("probe"));
    }
}
