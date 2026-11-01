package lang;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Properties;

/** What the VM fixes at the start, whatever is done to the properties, and who may hold what. */
public class StartupProperties {
    static void beside(String what, Runnable r) throws InterruptedException {
        Thread t = new Thread(r);
        // Holding System's own monitor must not stop the property calls.
        synchronized (System.class) {
            t.start();
            t.join(20000);
            System.out.println(what + " finished " + !t.isAlive());
        }
        t.join();
    }

    public static void main(String[] args) throws Exception {
        beside("first set", new Runnable() {
            @Override
            public void run() {
                System.setProperty("path.separator", "#");
            }
        });
        System.setProperty("file.separator", "#");
        System.setProperty("file.encoding", "ISO-8859-5");
        System.getProperties().setProperty("kept", "yes");
        beside("set table", new Runnable() {
            @Override
            public void run() {
                System.setProperties(new Properties());
            }
        });
        System.out.println("empty " + System.getProperties().isEmpty() + " " + System.getProperty("path.separator"));

        // File and the charset are first used now, with nothing in the table.
        System.out.println("path separator kept " + !File.pathSeparator.equals("#"));
        System.out.println("separator kept " + !File.separator.equals("#"));
        System.out.println("file " + new File("x").getPath() + " " + new File("a" + File.separator + "b").getName());
        System.out.println("charset kept " + !Charset.defaultCharset().name().equals("ISO-8859-5"));
    }
}
