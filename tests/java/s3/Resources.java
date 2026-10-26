package s3;

import java.io.IOException;
import java.io.InputStream;

/** Resources next to a class, from the root, through the system loader, and loaders. */
public class Resources {
    static String read(InputStream in) throws IOException {
        if (in == null) {
            return "null";
        }
        byte[] buf = new byte[256];
        int total = 0;
        int n;
        while ((n = in.read(buf, total, buf.length - total)) > 0) {
            total += n;
        }
        in.close();
        return new String(buf, 0, total, "UTF-8").trim();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("relative: " + read(Resources.class.getResourceAsStream("data.txt")));
        System.out.println("absolute: " + read(Resources.class.getResourceAsStream("/s3/sub/nested.txt")));
        System.out.println("sub relative: " + read(Resources.class.getResourceAsStream("sub/nested.txt")));
        System.out.println("missing: " + read(Resources.class.getResourceAsStream("nope.txt")));
        System.out.println("escape: " + read(Resources.class.getResourceAsStream("../s3/data.txt")));
        System.out.println("system: " + read(ClassLoader.getSystemResourceAsStream("s3/data.txt")));
        System.out.println("string loader null: " + (String.class.getClassLoader() == null));
        System.out.println("app loader set: " + (Resources.class.getClassLoader() != null));
        System.out.println("same loader: " + (Resources.class.getClassLoader() == ClassLoader.getSystemClassLoader()));
        System.out.println("int[] loader null: " + (int[].class.getClassLoader() == null));
        System.out.println("Resources[] loader set: " + (Resources[].class.getClassLoader() != null));
        System.out.println("forName: " + (Class.forName("s3.Resources", false, ClassLoader.getSystemClassLoader()) == Resources.class));
        System.out.println("boot forName String: " + Class.forName("java.lang.String", false, null).getName());
        try {
            Class.forName("s3.Resources", false, null);
            System.out.println("boot forName app: found");
        } catch (ClassNotFoundException e) {
            System.out.println("boot forName app: " + e.getClass().getName());
        }
        System.out.println("above root: " + read(Resources.class.getResourceAsStream("../../x.txt")));
        System.out.println("loadClass: " + ClassLoader.getSystemClassLoader().loadClass("s3.Resources").getName());
        System.out.println("done");
    }
}
