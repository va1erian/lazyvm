package thread;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;

/** Thread's context class loader: where it comes from, where it goes, what uses it. */
public class ContextLoaders {
    static final ClassLoader SYSTEM = ClassLoader.getSystemClassLoader();
    static final ClassLoader MINE = new ClassLoader() { };

    static String who(ClassLoader cl) {
        return cl == SYSTEM ? "system" : cl == MINE ? "mine" : cl == null ? "null" : "other";
    }

    static String of(Thread t) {
        return who(t.getContextClassLoader());
    }

    /** Its override shows whether a child asks through the method. */
    static class Overriding extends Thread {
        @Override
        public ClassLoader getContextClassLoader() {
            System.out.println("override asked by " + Thread.currentThread().getName());
            return MINE;
        }

        @Override
        public void run() {
            Thread child = new Thread("child");
            System.out.println("child of override " + who(childLoader(child)));
        }

        static ClassLoader childLoader(Thread child) {
            return child.getContextClassLoader();
        }
    }

    static void inheritance() throws Exception {
        Thread main = Thread.currentThread();
        System.out.println("main " + of(main) + ", own class " + who(ContextLoaders.class.getClassLoader()));
        Thread fresh = new Thread();
        System.out.println("new " + of(fresh));

        fresh.setContextClassLoader(MINE);
        Thread[] grandchild = new Thread[1];
        Thread parent = new Thread(fresh) {
            @Override
            public void run() {
                setContextClassLoader(MINE);
                grandchild[0] = new Thread();
            }
        };
        System.out.println("parent before start " + of(parent));
        parent.start();
        parent.join();
        System.out.println("grandchild " + of(grandchild[0]));
        System.out.println("parent ended " + of(parent));

        main.setContextClassLoader(null);
        System.out.println("main set null " + of(main) + ", new " + of(new Thread()));
        main.setContextClassLoader(MINE);
        System.out.println("main set mine " + of(main) + ", new " + of(new Thread()));
        main.setContextClassLoader(SYSTEM);

        Thread over = new Overriding();
        over.setName("over");
        over.start();
        over.join();
    }

    static void loading() throws Exception {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    System.out.println(cl.loadClass("java.lang.String").getName());
                    System.out.println(cl.loadClass("thread.ContextLoaders").getName());
                    cl.loadClass("no.such.Thing");
                } catch (ClassNotFoundException e) {
                    System.out.println(e);
                }
            }
        });
        t.start();
        t.join();
    }

    static void factories() throws Exception {
        Thread main = Thread.currentThread();
        ThreadFactory plain = Executors.defaultThreadFactory();
        main.setContextClassLoader(MINE);
        ThreadFactory privileged = Executors.privilegedThreadFactory();
        main.setContextClassLoader(SYSTEM);

        CountDownLatch ran = new CountDownLatch(1);
        Thread t = privileged.newThread(new Runnable() {
            @Override
            public void run() {
                System.out.println("privileged running " + of(Thread.currentThread()));
                ran.countDown();
            }
        });
        System.out.println("privileged unstarted " + of(t));
        t.start();
        ran.await();
        t.join();
        System.out.println("privileged ended " + of(t));
        System.out.println("privileged daemon " + t.isDaemon() + " priority " + t.getPriority());

        main.setContextClassLoader(MINE);
        System.out.println("plain " + of(plain.newThread(new Runnable() {
            @Override
            public void run() {
            }
        })));
        main.setContextClassLoader(SYSTEM);

        Thread noTask = Executors.privilegedThreadFactory().newThread(null);
        noTask.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread th, Throwable e) {
                System.out.println("no task " + e);
            }
        });
        noTask.start();
        noTask.join();
    }

    static void callables() throws Exception {
        Thread main = Thread.currentThread();
        Callable<String> shows = new Callable<String>() {
            @Override
            public String call() {
                return of(Thread.currentThread());
            }
        };
        main.setContextClassLoader(MINE);
        Callable<String> bound = Executors.privilegedCallableUsingCurrentClassLoader(shows);
        Callable<String> unbound = Executors.privilegedCallable(shows);
        main.setContextClassLoader(SYSTEM);
        System.out.println("bound " + bound.call() + ", after " + of(main));
        System.out.println("unbound " + unbound.call());
        Callable<String> throwing = new Callable<String>() {
            @Override
            public String call() throws Exception {
                throw new Exception("from call");
            }
        };
        main.setContextClassLoader(MINE);
        Callable<String> boundThrowing = Executors.privilegedCallableUsingCurrentClassLoader(throwing);
        main.setContextClassLoader(null);
        try {
            boundThrowing.call();
        } catch (Exception e) {
            System.out.println(e.getMessage() + ", after " + of(main));
        }
        main.setContextClassLoader(SYSTEM);
    }

    static void pools() throws Exception {
        Thread main = Thread.currentThread();
        main.setContextClassLoader(MINE);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        System.out.println("pool " + pool.submit(new Callable<String>() {
            @Override
            public String call() {
                return of(Thread.currentThread());
            }
        }).get());
        pool.shutdown();
        ForkJoinPool fork = new ForkJoinPool(1);
        System.out.println("fork " + fork.submit(new Callable<String>() {
            @Override
            public String call() {
                return of(Thread.currentThread());
            }
        }).get());
        fork.shutdown();
        // The common pool's workers inherit from whoever first needed them (observed).
        System.out.println("common " + ForkJoinPool.commonPool().submit(new Callable<String>() {
            @Override
            public String call() {
                return of(Thread.currentThread());
            }
        }).get());
        main.setContextClassLoader(SYSTEM);
    }

    public static void main(String[] args) throws Exception {
        inheritance();
        loading();
        factories();
        callables();
        pools();
    }
}
