package concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Executors: what each factory configures, the default thread factory, callable adapters. */
public class ExecutorFactories {
    interface Body { Object run() throws Throwable; }

    /** Only firstPool() pins pool numbers; later ones depend on how many factories ran. */
    static boolean numbersMatter = true;

    static String n(Object o) {
        String s = String.valueOf(o).replaceAll("@[0-9a-f]+", "@H");
        return numbersMatter ? s : s.replaceAll("pool-[0-9]+", "pool-N");
    }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    static String config(ExecutorService e) {
        if (!(e instanceof ThreadPoolExecutor)) return "not a ThreadPoolExecutor";
        ThreadPoolExecutor p = (ThreadPoolExecutor) e;
        return "core=" + p.getCorePoolSize() + " max=" + p.getMaximumPoolSize()
            + " keep=" + p.getKeepAliveTime(TimeUnit.SECONDS) + "s queue=" + p.getQueue().getClass().getName()
            + " handler=" + p.getRejectedExecutionHandler().getClass().getName();
    }

    /** Thread name, daemon flag and priority as seen from inside a task. */
    static String threadInfo(ExecutorService e) throws Exception {
        return e.submit(() -> {
            Thread t = Thread.currentThread();
            return t.getName() + " daemon=" + t.isDaemon() + " prio=" + t.getPriority() + " group=" + t.getThreadGroup().getName();
        }).get();
    }

    static void factories() throws Exception {
        ExecutorService fixed = Executors.newFixedThreadPool(2);
        show("fixed", () -> config(fixed));
        show("fixed toString", () -> fixed);
        show("fixed first thread", () -> threadInfo(fixed));
        fixed.shutdown();
        show("fixed 0", () -> Executors.newFixedThreadPool(0));
        show("fixed null factory", () -> Executors.newFixedThreadPool(1, null));

        ExecutorService cached = Executors.newCachedThreadPool();
        show("cached", () -> config(cached));
        show("cached thread", () -> threadInfo(cached));
        cached.shutdown();
        show("cached null factory", () -> Executors.newCachedThreadPool(null));

        ExecutorService single = Executors.newSingleThreadExecutor();
        show("single", () -> config(single));
        show("single thread", () -> threadInfo(single));
        show("single is ExecutorService", () -> single.isShutdown());
        single.shutdown();
        show("single terminated", () -> single.awaitTermination(30, TimeUnit.SECONDS));
        show("single null factory", () -> Executors.newSingleThreadExecutor(null));

        ScheduledExecutorService sched = Executors.newScheduledThreadPool(3);
        show("scheduled", () -> sched instanceof ScheduledThreadPoolExecutor);
        show("scheduled sizes", () -> ((ThreadPoolExecutor) sched).getCorePoolSize() + " " + ((ThreadPoolExecutor) sched).getMaximumPoolSize());
        show("scheduled keep", () -> ((ThreadPoolExecutor) sched).getKeepAliveTime(TimeUnit.SECONDS));
        show("scheduled negative", () -> Executors.newScheduledThreadPool(-1));
        sched.shutdown();
        ScheduledExecutorService singleS = Executors.newSingleThreadScheduledExecutor();
        show("single scheduled", () -> singleS instanceof ThreadPoolExecutor);
        ThreadFactory named = r -> new Thread(r, "named");
        show("scheduled factory", () -> {
            ScheduledExecutorService s = Executors.newScheduledThreadPool(1, named);
            String name = s.schedule(() -> Thread.currentThread().getName(), 0, TimeUnit.SECONDS).get();
            s.shutdown();
            return name;
        });
        show("scheduled null factory", () -> Executors.newScheduledThreadPool(1, null));
        show("single scheduled factory", () -> {
            ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(named);
            String name = s.schedule(() -> Thread.currentThread().getName(), 0, TimeUnit.SECONDS).get();
            s.shutdown();
            return name;
        });
        show("single scheduled null factory", () -> Executors.newSingleThreadScheduledExecutor(null));
        show("single scheduled runs", () -> singleS.schedule(() -> "ran", 0, TimeUnit.SECONDS).get());
        singleS.shutdown();

        ExecutorService stealing = Executors.newWorkStealingPool(2);
        show("stealing type", () -> stealing instanceof ForkJoinPool);
        show("stealing parallelism", () -> ((ForkJoinPool) stealing).getParallelism());
        show("stealing async", () -> ((ForkJoinPool) stealing).getAsyncMode());
        show("stealing daemon", () -> stealing.submit(() -> Thread.currentThread().isDaemon()).get());
        stealing.shutdown();
        show("stealing bad", () -> Executors.newWorkStealingPool(0));
        ExecutorService stealingDefault = Executors.newWorkStealingPool();
        show("stealing default parallelism",
            () -> ((ForkJoinPool) stealingDefault).getParallelism() == Runtime.getRuntime().availableProcessors());
        stealingDefault.shutdown();

        ThreadPoolExecutor raw = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        ExecutorService wrapped = Executors.unconfigurableExecutorService(raw);
        show("unconfigurable hides", () -> wrapped instanceof ThreadPoolExecutor);
        show("unconfigurable runs", () -> wrapped.submit(() -> "ran").get());
        wrapped.shutdown();
        show("unconfigurable passes shutdown", raw::isShutdown);
        show("unconfigurable null", () -> Executors.unconfigurableExecutorService(null));
        ScheduledThreadPoolExecutor rawS = new ScheduledThreadPoolExecutor(1);
        ScheduledExecutorService wrappedS = Executors.unconfigurableScheduledExecutorService(rawS);
        show("unconfigurable sched hides", () -> wrappedS instanceof ThreadPoolExecutor);
        show("unconfigurable sched runs", () -> wrappedS.schedule(() -> "ran", 0, TimeUnit.SECONDS).get());
        wrappedS.shutdown();
        show("unconfigurable sched null", () -> Executors.unconfigurableScheduledExecutorService(null));
    }

    static void defaultFactory() throws Exception {
        ThreadFactory f = Executors.defaultThreadFactory();
        Thread a = f.newThread(() -> { });
        Thread b = f.newThread(() -> { });
        show("name a", () -> a.getName().replaceAll("pool-[0-9]+", "pool-N"));
        show("name b", () -> b.getName().replaceAll("pool-[0-9]+", "pool-N"));
        show("not started", a::getState);
        show("not daemon", a::isDaemon);
        show("priority", a::getPriority);
        show("newThread null", () -> f.newThread(null));
        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
        Thread hi = f.newThread(() -> { });
        Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        show("priority resets", hi::getPriority);
        Thread[] made = new Thread[1];
        Thread daemonParent = new Thread(() -> made[0] = f.newThread(() -> { }));
        daemonParent.setDaemon(true);
        daemonParent.start();
        daemonParent.join();
        show("daemon parent not inherited", made[0]::isDaemon);
        ThreadFactory g = Executors.defaultThreadFactory();
        show("second factory numbered apart", () -> {
            String x = f.newThread(() -> { }).getName();
            String y = g.newThread(() -> { }).getName();
            return x.substring(0, x.indexOf("-thread")).equals(y.substring(0, y.indexOf("-thread")));
        });
        show("second factory restarts threads", () -> g.newThread(() -> { }).getName().endsWith("-thread-2"));
    }

    static void adapters() throws Exception {
        List<String> log = new ArrayList<>();
        Runnable r = () -> log.add("ran");
        show("callable", () -> Executors.callable(r).call());
        show("callable value", () -> Executors.callable(r, "v").call());
        show("callable null", () -> Executors.callable((Runnable) null));
        show("callable null with value", () -> Executors.callable(null, "v"));
        show("ran", () -> log);
        show("privilegedCallable", () -> Executors.privilegedCallable(() -> "pc").call());
        show("privilegedCallable null", () -> Executors.privilegedCallable(null));
        show("privilegedCallable loader", () -> Executors.privilegedCallableUsingCurrentClassLoader(() -> "pl").call());
        show("privilegedCallable loader null", () -> Executors.privilegedCallableUsingCurrentClassLoader(null));
        show("privilegedThreadFactory", () -> {
            Thread t = Executors.privilegedThreadFactory().newThread(() -> { });
            return t.getName() + " " + t.isDaemon() + " " + t.getPriority();
        });
        show("callable throw", () -> Executors.callable((Runnable) () -> { throw new IllegalStateException("x"); }).call());
    }

    /** The pool counter: the first factory made in a fresh VM is pool 1. Runs first. */
    static void firstPool() throws Exception {
        ExecutorService e = Executors.newFixedThreadPool(1);
        show("first pool thread", () -> threadInfo(e));
        e.shutdown();
        ExecutorService e2 = Executors.newCachedThreadPool();
        show("second pool thread", () -> threadInfo(e2));
        e2.shutdown();
    }

    public static void main(String[] args) throws Exception {
        firstPool();
        numbersMatter = false;
        factories();
        defaultFactory();
        adapters();
    }
}
