package lang;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Modifier;

/** System.setOut, setErr and setIn, including what the VM itself prints. */
public class SystemStreams {
    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        PrintStream err = System.err;
        InputStream in = System.in;

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        PrintStream replacement = new PrintStream(outBytes, true);
        System.setOut(replacement);
        System.out.print("captured");
        boolean same = System.out == replacement;
        System.setOut(out);
        out.println("out [" + outBytes + "] same " + same + ", back " + (System.out == out));

        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBytes, true));
        System.err.print("captured err");
        new Exception("printed").printStackTrace();
        Thread.setDefaultUncaughtExceptionHandler(null);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                throw new IllegalStateException("escaped");
            }
        }, "thrower");
        t.start();
        t.join();
        System.setErr(err);
        String[] lines = errBytes.toString().split("\r?\n");
        out.println("err first [" + lines[0] + "]");
        out.println("err uncaught " + errBytes.toString().contains(
                "Exception in thread \"thrower\" java.lang.IllegalStateException: escaped"));

        System.setIn(new ByteArrayInputStream(new byte[] {65, 66}));
        out.println("in " + System.in.read() + " " + System.in.read() + " " + System.in.read());
        System.setIn(in);
        out.println("in back " + (System.in == in));

        // Another thread sees the change.
        ByteArrayOutputStream other = new ByteArrayOutputStream();
        System.setOut(new PrintStream(other, true));
        Thread printer = new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.print("from a thread");
            }
        });
        printer.start();
        printer.join();
        System.setOut(out);
        out.println("thread [" + other + "]");

        // Null is allowed; the field then reads null.
        System.setOut(null);
        System.setErr(null);
        System.setIn(null);
        out.println("null " + System.out + " " + System.err + " " + System.in);
        try {
            System.out.println("nowhere");
        } catch (NullPointerException e) {
            out.println("null out " + e);
        }
        System.setOut(out);
        System.setErr(err);
        System.setIn(in);

        // Still final to reflection.
        for (String name : new String[] {"in", "out", "err"}) {
            int m = System.class.getField(name).getModifiers();
            out.println(name + " " + Modifier.toString(m));
        }

        // The last thing this program does goes to the replacement.
        readAfterRestore();
        ByteArrayOutputStream last = new ByteArrayOutputStream();
        System.setErr(new PrintStream(last, true));
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("main's exception in the replacement " + last.toString().startsWith(
                        "Exception in thread \"main\" java.io.IOException: uncaught in main"));
            }
        }));
        throw new IOException("uncaught in main");
    }

    static void readAfterRestore() {
        System.out.println("restored");
        System.err.println("restored err");
    }
}
