package thread;

import java.lang.reflect.Method;

/** A started thread nests calls from the VM into Java as deep as the main thread. */
public class DeepThread {
    static int viaReflection(int n) throws Exception {
        if (n == 0) {
            return 0;
        }
        Method m = DeepThread.class.getDeclaredMethod("viaReflection", int.class);
        return 1 + (Integer) m.invoke(null, n - 1);
    }

    public static void main(String[] args) throws Exception {
        final String[] out = new String[1];
        Thread t = new Thread(() -> {
            try {
                out[0] = "reflective depth " + viaReflection(1000);
            } catch (Throwable e) {
                out[0] = "failed: " + e;
            }
        });
        t.start();
        t.join();
        System.out.println(out[0]);
    }
}
