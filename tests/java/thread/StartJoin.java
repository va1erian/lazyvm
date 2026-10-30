package thread;

/** Thread start, join, names, daemons, priorities, handlers, locals and VM exit. */
public class StartJoin {
    static final int N = 8;
    static final long[] sums = new long[N];
    static final String[] names = new String[N];
    static final Object gate = new Object();
    static boolean open;

    static class Boom extends Thread {
        Boom(String name) {
            super(name);
        }

        public void run() {
            throw new IllegalStateException("boom in " + getName());
        }
    }

    public static void main(String[] args) throws Exception {
        Thread main = Thread.currentThread();
        System.out.println(main + " alive=" + main.isAlive() + " daemon=" + main.isDaemon()
                + " state=" + main.getState() + " group=" + main.getThreadGroup().getName()
                + " parent=" + main.getThreadGroup().getParent().getName());
        workers();
        lifecycle();
        daemons();
        priorities();
        handlers();
        locals();
        lastThread();
    }

    static void workers() throws InterruptedException {
        Thread[] ts = new Thread[N];
        for (int i = 0; i < N; i++) {
            final int n = i;
            ts[i] = new Thread(() -> {
                long s = 0;
                for (int k = 0; k <= 20000 * (n + 1); k++) {
                    s += k % 7;
                }
                sums[n] = s;
                names[n] = Thread.currentThread().getName();
            });
        }
        System.out.println("before start: " + ts[0].getState() + " alive=" + ts[0].isAlive());
        for (Thread t : ts) {
            t.start();
        }
        for (Thread t : ts) {
            t.join();
        }
        for (int i = 0; i < N; i++) {
            System.out.println(names[i] + " sum=" + sums[i] + " " + ts[i].getState()
                    + " alive=" + ts[i].isAlive());
        }
        try {
            ts[0].start();
        } catch (IllegalThreadStateException e) {
            System.out.println("start twice: " + e);
        }
        System.out.println("terminated toString: " + ts[0]);
    }

    static void lifecycle() throws InterruptedException {
        Thread sleeper = new Thread(() -> {
            synchronized (gate) {
                while (!open) {
                    try {
                        gate.wait();
                    } catch (InterruptedException e) {
                        System.out.println("unexpected interrupt");
                    }
                }
            }
        }, "sleeper");
        sleeper.start();
        sleeper.join(20);
        System.out.println("after join(20): alive=" + sleeper.isAlive());
        try {
            sleeper.setDaemon(true);
        } catch (IllegalThreadStateException e) {
            System.out.println("setDaemon on a live thread: " + e);
        }
        synchronized (gate) {
            open = true;
            gate.notifyAll();
        }
        sleeper.join();
        System.out.println("after join(): alive=" + sleeper.isAlive());
        Thread never = new Thread("never");
        never.join();
        never.join(5);
        System.out.println("join of an unstarted thread returns: " + never.getState());
        try {
            never.join(-1);
        } catch (IllegalArgumentException e) {
            System.out.println("join(-1): " + e.getMessage());
        }
        Thread.yield();
    }

    static void daemons() throws InterruptedException {
        final boolean[] childDaemon = new boolean[1];
        Thread parent = new Thread(() -> {
            Thread child = new Thread(() -> { });
            childDaemon[0] = child.isDaemon();
        });
        parent.setDaemon(true);
        parent.start();
        parent.join();
        System.out.println("parent daemon=" + parent.isDaemon() + " child daemon=" + childDaemon[0]);
        // A daemon blocked forever must not keep the VM alive.
        Thread forever = new Thread(() -> {
            Object o = new Object();
            synchronized (o) {
                try {
                    o.wait();
                } catch (InterruptedException e) {
                    System.out.println("unexpected interrupt");
                }
            }
        }, "forever");
        forever.setDaemon(true);
        forever.start();
    }

    static void priorities() throws InterruptedException {
        Thread t = new Thread("prio");
        System.out.println("default priority " + t.getPriority());
        t.setPriority(Thread.MAX_PRIORITY);
        final int[] inherited = new int[1];
        Thread p = new Thread(() -> inherited[0] = new Thread("c").getPriority());
        p.setPriority(3);
        p.start();
        p.join();
        System.out.println("set " + t.getPriority() + ", inherited " + inherited[0]);
        try {
            t.setPriority(11);
        } catch (IllegalArgumentException e) {
            System.out.println("priority 11: " + e);
        }
    }

    static void handlers() throws InterruptedException {
        Thread own = new Boom("own");
        own.setUncaughtExceptionHandler((th, e) ->
                System.out.println("own handler: " + th.getName() + " " + e.getMessage()));
        own.start();
        own.join();
        Thread.setDefaultUncaughtExceptionHandler((th, e) ->
                System.out.println("default handler: " + th.getName() + " " + e.getMessage()));
        Thread viaDefault = new Boom("viaDefault");
        System.out.println("handler is the group: "
                + (viaDefault.getUncaughtExceptionHandler() == viaDefault.getThreadGroup()));
        viaDefault.start();
        viaDefault.join();
        Thread.setDefaultUncaughtExceptionHandler(null);
        // No handler at all: the group prints it on stderr, and life goes on.
        Thread printed = new Boom("printed");
        printed.start();
        printed.join();
        System.out.println("after printed: " + printed.getState());
    }

    static final ThreadLocal<String> local = ThreadLocal.withInitial(() -> "initial");
    static final InheritableThreadLocal<String> inherited = new InheritableThreadLocal<String>() {
        protected String childValue(String parent) {
            return parent + "+child";
        }
    };

    static void locals() throws InterruptedException {
        local.set("main value");
        inherited.set("main");
        final String[] seen = new String[3];
        Thread t = new Thread(() -> {
            seen[0] = local.get();
            local.set("thread value");
            seen[1] = local.get();
            seen[2] = inherited.get();
            inherited.set("changed in thread");
        });
        t.start();
        t.join();
        System.out.println("thread saw " + seen[0] + ", " + seen[1] + ", " + seen[2]);
        System.out.println("main has " + local.get() + ", " + inherited.get());
        local.remove();
        System.out.println("after remove: " + local.get());
        ThreadLocal<Integer> plain = new ThreadLocal<Integer>();
        System.out.println("no initial value: " + plain.get());
    }

    /** main returns first; the VM waits for this thread before exiting. */
    static void lastThread() {
        Thread last = new Thread(() -> {
            synchronized (gate) {
                while (open) {
                    try {
                        gate.wait();
                    } catch (InterruptedException e) {
                        System.out.println("unexpected interrupt");
                    }
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                System.out.println("unexpected interrupt");
            }
            System.out.println("last thread done after main");
        }, "last");
        last.start();
        System.out.println("main returns");
        synchronized (gate) {
            open = false;
            gate.notifyAll();
        }
    }
}
