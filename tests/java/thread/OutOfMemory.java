// VMARGS: -Xmx16m
package thread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Running out of heap is an error like any other, wherever it strikes: in main
 * and in other threads, in a class initializer and while the VM creates another
 * exception. Each is caught, and the VM goes on working once memory is free.
 * The heap is full during the checks, so results are kept in fields made ahead
 * of time and printed once it has been let go.
 */
public class OutOfMemory {
    static final int BIG = 8 << 20;

    /** Fails for good: its initializer runs out of memory. */
    static class Fails {
        static byte[] data = new byte[BIG];

        static int use() {
            return data.length;
        }
    }

    /** Its superclass failed, so this one did too. */
    static class FailsToo extends Fails {
        static int use2() {
            return 2;
        }
    }

    /** Copes with the shortage, so it initializes. */
    static class Copes {
        static boolean coped;

        static {
            try {
                byte[] b = new byte[BIG];
                coped = b == null;
            } catch (OutOfMemoryError e) {
                coped = true;
            }
        }
    }

    /** Needs no memory: a full heap is no reason to fail. */
    static class NoAllocation {
        static int seven = 7;
    }

    static final class Node {
        final Node next;

        Node(Node next) {
            this.next = next;
        }
    }

    static List<Object> full;
    static final Class<?>[] seen = new Class<?>[8];

    static void fill() {
        full = new ArrayList<>(1 << 16);
        try {
            while (true) {
                full.add(new byte[64 << 10]);
            }
        } catch (OutOfMemoryError e) {
            // BIG can no longer fit; that is all this needs.
        }
    }

    static void note(int at, Class<?> c) {
        seen[at] = c;
    }

    /** Fills every last gap, unlike {@link #fill}. */
    static void fillTight() {
        fill();
        for (int size = 32 << 10; size >= 16; size /= 2) {
            try {
                while (true) {
                    full.add(new byte[size]);
                }
            } catch (OutOfMemoryError e) {
                // On to a smaller size.
            }
        }
    }

    static void withFullHeap() throws Exception {
        // Loaded but not initialized, so the initializers meet the full heap.
        ClassLoader cl = OutOfMemory.class.getClassLoader();
        for (Class<?> c : new Class<?>[] {Fails.class, FailsToo.class, Copes.class, NoAllocation.class}) {
            Class.forName(c.getName(), false, cl);
        }
        fill();
        try {
            note(0, Fails.use() == 0 ? Fails.class : Copes.class);
        } catch (Throwable t) {
            note(0, t.getClass());
        }
        try {
            note(1, FailsToo.use2() == 2 ? FailsToo.class : Copes.class);
        } catch (Throwable t) {
            note(1, t.getClass());
        }
        try {
            note(2, Fails.use() == 0 ? Fails.class : Copes.class);
        } catch (Throwable t) {
            note(2, t.getClass());
        }
        note(3, Copes.coped ? Copes.class : Fails.class);
        note(4, NoAllocation.seven == 7 ? NoAllocation.class : Fails.class);
        full = null;
    }

    /**
     * The library's classes, first used while nothing can be allocated. This may
     * work or not, but it must not break them for later: the oracle has most of
     * them ready by now.
     */
    static void libraryClasses() {
        final Object[] tries = new Object[3];
        fillTight();
        try {
            tries[0] = TimeUnit.SECONDS;
        } catch (Throwable t) {
            tries[0] = t;
        }
        try {
            tries[1] = Collections.EMPTY_MAP;
        } catch (Throwable t) {
            tries[1] = t;
        }
        try {
            tries[2] = Locale.ROOT;
        } catch (Throwable t) {
            tries[2] = t;
        }
        full = null;
        System.out.println(TimeUnit.SECONDS + " " + Collections.EMPTY_MAP + " [" + Locale.ROOT + "]");
    }

    /**
     * Natives may go past the limit where a bytecode may not (clone here), and
     * with them the heap can really be too full for the exception's own objects.
     */
    static void clonesOverTheLimit() {
        Object[] keep = new Object[1 << 18];
        int n = 0;
        String stopped = "not stopped";
        byte[][] pieces = new byte[5][];
        for (int i = 0; i < pieces.length; i++) {
            pieces[i] = new byte[(1 << (20 - 4 * i)) - 16];
        }
        for (byte[] piece : pieces) {
            try {
                while (n < keep.length) {
                    keep[n++] = piece.clone();
                }
            } catch (OutOfMemoryError e) {
                stopped = "stopped";
                n--;
            }
        }
        // Over the limit, code that allocates nothing still runs at full speed.
        long sum = 0;
        for (int i = 0; i < 2_000_000; i++) {
            sum += i;
        }
        keep = null;
        System.out.println("clones " + stopped + ", then " + sum);
    }

    /** A handler can report what it caught although nothing was freed. */
    static void reportInHandler() {
        Node keep = null;
        int n = 0;
        try {
            while (true) {
                keep = new Node(keep);
                n++;
            }
        } catch (OutOfMemoryError e) {
            System.out.println("in the handler: " + e.getClass().getName()
                    + ", nodes kept: " + (keep != null && n > 1000));
        }
    }

    /** Fills the heap, catches the error, and does it again with the monitor held. */
    static void exhaust(Object lock, int[] caught) {
        for (int round = 0; round < 2; round++) {
            List<byte[]> mine = new ArrayList<>();
            try {
                synchronized (lock) {
                    while (true) {
                        mine.add(new byte[16 << 10]);
                    }
                }
            } catch (OutOfMemoryError e) {
                mine = null;
                synchronized (caught) {
                    caught[0]++;
                }
            }
        }
    }

    static void inOtherThreads() throws Exception {
        final int[] caught = {0};
        final Object lock = new Object();
        Thread[] ts = new Thread[3];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> exhaust(lock, caught));
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        System.out.println("caught in threads: " + caught[0]);
    }

    /** An error nobody catches goes to the handler, and the thread still ends. */
    static void uncaught() throws Exception {
        final String[] handled = new String[1];
        Thread t = new Thread(() -> {
            List<byte[]> mine = new ArrayList<>();
            while (true) {
                mine.add(new byte[16 << 10]);
            }
        });
        t.setUncaughtExceptionHandler((th, e) -> handled[0] = e.getClass().getName());
        t.start();
        t.join();
        System.out.println("uncaught: " + handled[0] + ", alive: " + t.isAlive());
    }

    /** A failed class goes on failing, on any thread, with its own message. */
    static void stillFails() throws Exception {
        final String[] out = new String[1];
        Thread t = new Thread(() -> {
            try {
                out[0] = "use: " + Fails.use();
            } catch (Throwable e) {
                out[0] = e.toString();
            }
        });
        t.start();
        t.join();
        System.out.println(out[0]);
    }

    static void usable() throws Exception {
        byte[] b = new byte[BIG];
        StringBuilder sb = new StringBuilder();
        Runnable r = () -> sb.append("lambda ");
        r.run();
        Thread t = new Thread(() -> sb.append("thread "));
        t.start();
        t.join();
        try {
            Object o = null;
            o.hashCode();
        } catch (NullPointerException e) {
            sb.append("npe ");
        }
        System.out.println(sb + "" + b.length);
    }

    public static void main(String[] args) throws Exception {
        withFullHeap();
        for (Class<?> c : seen) {
            if (c != null) {
                System.out.println(c.getName());
            }
        }
        libraryClasses();
        clonesOverTheLimit();
        reportInHandler();
        inOtherThreads();
        uncaught();
        stillFails();
        usable();
    }
}
