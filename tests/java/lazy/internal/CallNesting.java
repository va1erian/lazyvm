package lazy.internal;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Calls from the VM into Java nest to the VM's limit and end in a StackOverflowError, on the main
 * thread and a started one, with native stack to spare on every platform. A hand-written golden, as
 * the limit is LazyVM's (decisions.md D15); no line says how far past 2000 it is.
 */
public class CallNesting {
    static Method method;
    static Constructor<?> constructor;
    static int deepest;

    public CallNesting(int depth) throws Exception {
        deepest = depth;
        constructor.newInstance(depth + 1);
    }

    static void invoked(int depth) throws Exception {
        deepest = depth;
        method.invoke(null, depth + 1);
    }

    interface Dive {
        void from(int depth) throws Exception;
    }

    static String run(Dive dive) {
        deepest = 0;
        try {
            dive.from(0);
            return "returned";
        } catch (Throwable t) {
            Throwable root = t;
            while (root instanceof InvocationTargetException) {
                root = root.getCause();
            }
            return root.getClass().getName() + " past 2000: " + (deepest > 2000);
        }
    }

    static void all(String where) {
        System.out.println(where + " invoke: " + run(CallNesting::invoked));
        System.out.println(where + " newInstance: " + run(d -> constructor.newInstance(d)));
    }

    public static void main(String[] args) throws Exception {
        method = CallNesting.class.getDeclaredMethod("invoked", int.class);
        constructor = CallNesting.class.getConstructor(int.class);
        all("main");
        Thread t = new Thread(() -> all("thread"));
        t.start();
        t.join();
    }
}
