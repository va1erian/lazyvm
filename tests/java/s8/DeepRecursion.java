// TIMEOUT: 180
package s8;

import java.lang.reflect.Method;

/** Running out of stack is a catchable StackOverflowError, however the stack was used. */
public class DeepRecursion {
    static int depth;

    static void plain() {
        depth++;
        plain();
    }

    static long wide(long a, long b, long c, long d, long e, long f) {
        long x = a + b;
        return wide(x, c, d, e, f, x) + 1;
    }

    public static void viaReflection() throws Exception {
        Method self = DeepRecursion.class.getMethod("viaReflection");
        self.invoke(null);
    }

    static void viaLambda() {
        Runnable r = DeepRecursion::viaLambda;
        r.run();
    }

    static class Node {
        Node next = new Node();
    }

    static void synchronizedDeep(Object lock) {
        synchronized (lock) {
            synchronizedDeep(lock);
        }
    }

    interface Case {
        void run() throws Exception;
    }

    static void expect(String name, Case c) {
        try {
            c.run();
            System.out.println(name + ": returned");
        } catch (StackOverflowError e) {
            System.out.println(name + ": StackOverflowError");
        } catch (java.lang.reflect.InvocationTargetException e) {
            System.out.println(name + ": " + rootName(e));
        } catch (Throwable t) {
            System.out.println(name + ": " + t.getClass().getName());
        }
    }

    /** Reflection wraps at any depth, so only the innermost cause is stable. */
    static String rootName(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getName();
    }

    public static void main(String[] args) throws Exception {
        expect("plain", DeepRecursion::plain);
        System.out.println("went deep: " + (depth > 500));
        expect("wide", () -> wide(1, 2, 3, 4, 5, 6));
        expect("reflection", DeepRecursion::viaReflection);
        expect("lambda", DeepRecursion::viaLambda);
        expect("constructor", Node::new);
        Object lock = new Object();
        expect("synchronized", () -> synchronizedDeep(lock));
        // The monitors of the unwound frames are all released.
        Thread t = new Thread(() -> {
            synchronized (lock) {
                System.out.println("lock is free");
            }
            expect("other thread", DeepRecursion::plain);
        });
        t.start();
        t.join();
        expect("again", DeepRecursion::plain);
        System.out.println("done");
    }
}
