// VMARGS: -Xmx16m
package thread;

/**
 * A native takes the heap past the limit, where no allocating bytecode can go:
 * the handler of the error it ends in can still do a little, with the data live.
 */
public class OutOfMemoryHandlerRoom {
    static Object[] retained;

    static void clones() {
        byte[] b = new byte[1 << 19];
        Object[] h = new Object[256];
        retained = h;
        int n = 0;
        try {
            for (; n < h.length; n++) {
                h[n] = b.clone();
            }
            System.out.println("no error");
        } catch (OutOfMemoryError e) {
            System.out.println("clones, caught " + e.getMessage() + ", some made: " + (n > 0));
        }
        System.out.println("kept: " + (h[0] != null));
        retained = null;
    }

    static void builder() {
        try {
            StringBuilder sb = new StringBuilder("x");
            for (;;) {
                sb.append(sb);
            }
        } catch (OutOfMemoryError e) {
            System.out.println("builder, caught " + e.getMessage());
        }
    }

    static void concat() {
        try {
            String z = new String(new char[1 << 19]);
            for (;;) {
                z = z.concat(z);
            }
        } catch (OutOfMemoryError e) {
            System.out.println("concat, caught " + e.getMessage());
        }
    }

    static void onThread() throws Exception {
        final Object[] h = new Object[256];
        Thread t = new Thread(() -> {
            try {
                for (int n = 0; n < h.length; n++) {
                    h[n] = new byte[1 << 19].clone();
                }
            } catch (OutOfMemoryError e) {
                System.out.println("thread, caught " + e.getMessage());
            }
        });
        t.start();
        t.join();
        System.out.println("kept: " + (h[0] != null));
    }

    public static void main(String[] args) throws Exception {
        clones();
        builder();
        concat();
        onThread();
    }
}
