package lazy.internal;

import java.util.concurrent.*;

/**
 * What an overflow leaves of a pool, with each pool call at every depth down to the end of a stack.
 * A hand-written golden: the oracle's pool often does not end after this, and a runaway at a high
 * parallelism often hangs it (concurrent.md, "What is not pinned"). Here the counts stay right and
 * a runaway makes few workers (it once made 5475 and took 3 GB).
 */
public class ForkJoinOverflow {
    static boolean reached;

    interface Act { void go() throws Exception; }

    static void dive(Act act) {
        try { dive(act); } catch (StackOverflowError e) { }
        if (!reached) {
            try {
                act.go();
                reached = true;
            } catch (StackOverflowError e) {
                throw e;
            } catch (Exception e) {
                reached = true;
            }
        }
    }

    static class Noop extends RecursiveAction { @Override protected void compute() { } }

    static Act make(String mode, ForkJoinTask<?> pending) {
        switch (mode) {
            case "quietlyJoin": return pending::quietlyJoin;
            case "join": return pending::join;
            case "get": return pending::get;
            case "timed get": return () -> pending.get(5, TimeUnit.SECONDS);
            case "fork and join": return () -> { Noop n = new Noop(); n.fork(); n.join(); };
            case "fork and timed get": return () -> { Noop n = new Noop(); n.fork(); n.get(5, TimeUnit.SECONDS); };
            case "invoke": return () -> new Noop().invoke();
            case "quietlyInvoke": return () -> new Noop().quietlyInvoke();
            case "invokeAll": return () -> ForkJoinTask.invokeAll(new Noop(), new Noop());
            case "helpQuiesce": return ForkJoinTask::helpQuiesce;
            case "awaitQuiescence": return () -> ForkJoinTask.getPool().awaitQuiescence(1, TimeUnit.SECONDS);
            default: return () -> ForkJoinPool.managedBlock(new ForkJoinPool.ManagedBlocker() {
                @Override public boolean block() { return true; }
                @Override public boolean isReleasable() { return false; }
            });
        }
    }

    static String one(String mode, int par) throws Exception {
        ForkJoinPool x = new ForkJoinPool(par);
        reached = false;
        x.invoke(new Noop());
        Noop pending = new Noop();
        Thread release = new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException e) { }
            pending.complete(null);
        });
        release.setDaemon(true);
        ForkJoinTask<?> t = x.submit(() -> { release.start(); dive(make(mode, pending)); });
        t.get(30, TimeUnit.SECONDS);
        Thread.sleep(30);
        String counts = x.getRunningThreadCount() + " " + x.getActiveThreadCount();
        CountDownLatch next = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) x.execute(next::countDown);
        boolean ran = next.await(30, TimeUnit.SECONDS);
        String back = x.submit(() -> { ForkJoinTask.helpQuiesce(); return "back"; }).get(30, TimeUnit.SECONDS);
        boolean quiet = x.awaitQuiescence(30, TimeUnit.SECONDS);
        x.shutdown();
        return "counts " + counts + ", next " + ran + ", helpQuiesce " + back + ", quiet " + quiet + ", ends " + x.awaitTermination(30, TimeUnit.SECONDS);
    }

    static class Runaway extends RecursiveTask<Integer> {
        @Override protected Integer compute() { Runaway c = new Runaway(); c.fork(); return c.join() + 1; }
    }

    static String outcome(Future<?> f) throws Exception {
        try {
            return String.valueOf(f.get(30, TimeUnit.SECONDS));
        } catch (ExecutionException e) {
            return e.getCause().getClass().getName();
        }
    }

    static class Chain extends RecursiveTask<Integer> {
        final int d;
        Chain(int d) { this.d = d; }
        @Override protected Integer compute() {
            if (d == 0) return 0;
            Chain c = new Chain(d - 1);
            c.fork();
            return c.join() + 1;
        }
    }

    static class RunawayInvoke extends RecursiveTask<Integer> {
        @Override protected Integer compute() { return new RunawayInvoke().invoke() + 1; }
    }

    static void runaways() throws Exception {
        for (int par : new int[] {1, 2, 4, 8, 24, 1000}) {
            ForkJoinPool x = new ForkJoinPool(par);
            System.out.println("runaway at " + par + ": " + outcome(x.submit(new Runaway())) + ", few workers " + (x.getPoolSize() <= 20));
            x.shutdown();
            System.out.println("  ends " + x.awaitTermination(30, TimeUnit.SECONDS));
        }
        ForkJoinPool z = new ForkJoinPool(2);
        System.out.println("chain of a million: " + outcome(z.submit(new Chain(1000000))));
        System.out.println("then a task: " + outcome(z.submit(() -> "next")));
        z.shutdown();
        System.out.println("  ends " + z.awaitTermination(30, TimeUnit.SECONDS));
        ForkJoinPool y = new ForkJoinPool(4);
        System.out.println("runaway invokes: " + outcome(y.submit(new RunawayInvoke())) + ", few workers " + (y.getPoolSize() <= 20));
        y.shutdown();
        System.out.println("  ends " + y.awaitTermination(30, TimeUnit.SECONDS));
    }

    public static void main(String[] args) throws Exception {
        String[] modes = {"quietlyJoin", "join", "get", "timed get", "fork and join", "fork and timed get", "invoke",
            "quietlyInvoke", "invokeAll", "helpQuiesce", "awaitQuiescence", "managedBlock"};
        for (int par : new int[] {1, 2}) {
            for (String mode : modes) {
                System.out.println(mode + " at " + par + ": " + one(mode, par));
            }
        }
        runaways();
        System.exit(0);
    }
}
