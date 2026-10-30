package concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** CompletableFuture itself: states, toString, every way out of get/join/getNow, cancel, obtrude. */
public class CompletableFutures {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + chain(t)); }
    }

    interface Cond { boolean ok() throws Exception; }

    static void until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("stuck");
            Thread.sleep(1);
        }
    }

    /** Class and message of a throwable and each of its causes. */
    static String chain(Throwable t) {
        String s = t.getClass().getSimpleName() + "(" + t.getMessage() + ")";
        return t.getCause() == null ? s : s + " <- " + chain(t.getCause());
    }

    /** All the ways to read a future: flags, join, get, getNow and what a dependent receives. */
    static String full(CompletableFuture<?> f) {
        StringBuilder b = new StringBuilder();
        b.append(f.isDone() ? "done" : "pending");
        if (f.isCancelled()) b.append(" cancelled");
        if (f.isCompletedExceptionally()) b.append(" failed");
        try { Object v = f.getNow(null); b.append("; getNow=").append(v); } catch (Throwable t) { b.append("; getNow!").append(chain(t)); }
        if (f.isDone()) {
            try { Object v = f.join(); b.append("; join=").append(v); } catch (Throwable t) { b.append("; join!").append(chain(t)); }
            try { Object v = f.get(); b.append("; get=").append(v); } catch (Throwable t) { b.append("; get!").append(chain(t)); }
            String[] raw = new String[1];
            f.whenComplete((v, t) -> raw[0] = t == null ? "value " + v : chain(t));
            b.append("; handed=").append(raw[0]);
        }
        return b.toString();
    }

    static void states() throws Exception {
        CompletableFuture<String> f = new CompletableFuture<>();
        show("new toString", () -> f);
        show("new", () -> full(f));
        show("getNow default", () -> f.getNow("dflt"));
        show("dependents", f::getNumberOfDependents);
        show("timed get", () -> f.get(1, TimeUnit.MILLISECONDS));
        show("zero get", () -> f.get(0, TimeUnit.SECONDS));
        show("null unit pending", () -> f.get(1, null));
        show("toCompletableFuture", () -> f.toCompletableFuture() == f);
        show("complete", () -> f.complete("v"));
        show("complete again", () -> f.complete("w"));
        show("completeExceptionally late", () -> f.completeExceptionally(new RuntimeException("x")));
        show("cancel late", () -> f.cancel(true));
        show("done toString", () -> f);
        show("done", () -> full(f));
        show("getNow done", () -> f.getNow("dflt"));
        show("timed get done", () -> f.get(1, TimeUnit.SECONDS));
        show("null unit done", () -> f.get(1, null));
        CompletableFuture<String> nul = new CompletableFuture<>();
        show("complete null", () -> nul.complete(null));
        show("null value", () -> full(nul));
        show("null getNow", () -> nul.getNow("dflt"));
        show("completedFuture", () -> full(CompletableFuture.completedFuture("cf")));
        show("completedFuture null", () -> full(CompletableFuture.completedFuture(null)));
        show("completedFuture toString", () -> CompletableFuture.completedFuture(1));
    }

    static void failures() {
        Throwable[] all = {
            new java.io.IOException("io"), new IllegalStateException("ise"), new StackOverflowError("err"),
            new CompletionException("ce", new RuntimeException("inner")), new CompletionException(new RuntimeException("inner2")),
            new CompletionException("no cause", null), new CancellationException("cx"), new ExecutionException("ee", new RuntimeException("inner3")),
            new Exception(),
        };
        for (Throwable t : all) {
            CompletableFuture<String> f = new CompletableFuture<>();
            show("fail with " + chain(t), () -> f.completeExceptionally(t));
            show("  toString", () -> f);
            show("  read", () -> full(f));
            show("  again", () -> f.completeExceptionally(new RuntimeException("y")));
            show("  complete late", () -> f.complete("v"));
        }
        CompletableFuture<String> f = new CompletableFuture<>();
        show("fail null", () -> f.completeExceptionally(null));
        show("fail null leaves", () -> full(f));
        show("identity join cause", () -> {
            RuntimeException r = new RuntimeException("same");
            CompletableFuture<String> g = new CompletableFuture<>();
            g.completeExceptionally(r);
            try { g.join(); return "no"; } catch (CompletionException e) { return e.getCause() == r; }
        });
        show("identity CE not rewrapped", () -> {
            CompletionException ce = new CompletionException(new RuntimeException("same"));
            CompletableFuture<String> g = new CompletableFuture<>();
            g.completeExceptionally(ce);
            try { g.join(); return "no"; } catch (CompletionException e) { return e == ce; }
        });
    }

    static void cancels() {
        for (boolean flag : new boolean[] {true, false}) {
            CompletableFuture<String> f = new CompletableFuture<>();
            show("cancel(" + flag + ")", () -> f.cancel(flag));
            show("cancelled toString", () -> f);
            show("cancelled read", () -> full(f));
            show("cancel again", () -> f.cancel(!flag));
            show("complete after cancel", () -> f.complete("v"));
            show("fail after cancel", () -> f.completeExceptionally(new RuntimeException("x")));
        }
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("x"));
        show("cancel failed", () -> failed.cancel(true));
        show("failed not cancelled", failed::isCancelled);
        CompletableFuture<String> viaFail = new CompletableFuture<>();
        viaFail.completeExceptionally(new CancellationException());
        show("cancel when failed with CE", () -> viaFail.cancel(true));
        CompletableFuture<String> sub = new CompletableFuture<String>();
        CompletableFuture<String> dep = sub.thenApply(s -> s + "!");
        sub.cancel(false);
        show("dependent of cancelled", () -> full(dep));
        show("dep cancelled flag", dep::isCancelled);
        CompletableFuture<String> parent = new CompletableFuture<>();
        CompletableFuture<String> child = parent.thenApply(s -> s + "!");
        child.cancel(false);
        show("cancelling child leaves parent", parent::isDone);
        parent.complete("v");
        show("parent completes", () -> full(parent));
        show("child stays cancelled", child::isCancelled);
    }

    static void obtrudes() {
        List<String> runs = new ArrayList<>();
        CompletableFuture<String> f = new CompletableFuture<>();
        f.thenAccept(v -> runs.add("dep " + v));
        f.obtrudeValue("o1");
        show("obtrude pending", () -> full(f));
        show("obtrude fires dependents", () -> runs);
        f.thenAccept(v -> runs.add("later " + v));
        show("later dep", () -> runs);
        f.obtrudeValue("o2");
        show("obtrude done", () -> full(f));
        show("no refire", () -> runs);
        f.obtrudeException(new IllegalStateException("oe"));
        show("obtrude exception over value", () -> full(f));
        show("toString", () -> f);
        f.obtrudeException(new CancellationException("cx"));
        show("obtrude cancellation", () -> full(f));
        show("isCancelled", f::isCancelled);
        f.obtrudeValue(null);
        show("obtrude null value", () -> full(f));
        show("obtrude null exception", () -> { f.obtrudeException(null); return "no"; });
        show("left", () -> full(f));
        CompletableFuture<String> c = new CompletableFuture<>();
        c.cancel(true);
        c.obtrudeValue("back");
        show("obtrude over cancel", () -> full(c));
        show("complete after obtrude", () -> c.complete("x"));
        CompletableFuture<String> p = new CompletableFuture<>();
        CompletableFuture<String> d = p.thenApply(s -> s + "!");
        p.complete("a");
        p.obtrudeValue("b");
        show("dep keeps old", () -> d.join());
        CompletableFuture<String> e = new CompletableFuture<>();
        e.obtrudeException(new RuntimeException("first"));
        show("obtrude exception pending", () -> full(e));
    }

    static void interrupts() throws Exception {
        CompletableFuture<String> f = new CompletableFuture<>();
        Thread me = Thread.currentThread();
        me.interrupt();
        show("get flagged pending", f::get);
        show("flag cleared", me::isInterrupted);
        me.interrupt();
        show("timed get flagged pending", () -> f.get(1, TimeUnit.SECONDS));
        show("flag cleared 2", me::isInterrupted);
        CompletableFuture<String> done = CompletableFuture.completedFuture("v");
        me.interrupt();
        show("get flagged done", done::get);
        show("flag kept", me::isInterrupted);
        show("timed get flagged done", () -> done.get(1, TimeUnit.SECONDS));
        show("join flagged done", done::join);
        show("flag kept 2", Thread::interrupted);
        String[] res = new String[1];
        Thread g = new Thread(() -> {
            try { res[0] = f.get(); }
            catch (Exception e) { res[0] = chain(e) + " flag=" + Thread.currentThread().isInterrupted(); }
        });
        g.start();
        until(() -> g.getState() == Thread.State.WAITING);
        g.interrupt();
        g.join();
        show("get interrupted", () -> res[0]);
        show("future untouched", f::isDone);
        Thread j = new Thread(() -> res[0] = f.join() + " flag=" + Thread.currentThread().isInterrupted());
        j.start();
        until(() -> j.getState() == Thread.State.WAITING);
        j.interrupt();
        Thread.sleep(5);
        show("join ignores interrupt", j::getState);
        f.complete("v");
        j.join();
        show("join return", () -> res[0]);
        CompletableFuture<String> w = new CompletableFuture<>();
        String[] waiters = new String[2];
        Thread g1 = new Thread(() -> { try { waiters[0] = w.get(); } catch (Exception e) { waiters[0] = chain(e); } });
        Thread g2 = new Thread(() -> { try { waiters[1] = w.join(); } catch (Exception e) { waiters[1] = chain(e); } });
        g1.start();
        g2.start();
        until(() -> g1.getState() == Thread.State.WAITING && g2.getState() == Thread.State.WAITING);
        show("blocked waiters count as dependents", w::getNumberOfDependents);
        w.completeExceptionally(new RuntimeException("x"));
        g1.join();
        g2.join();
        show("get waiter", () -> waiters[0]);
        show("join waiter", () -> waiters[1]);
        CompletableFuture<String> t = new CompletableFuture<>();
        Thread tg = new Thread(() -> {
            try { res[0] = t.get(30, TimeUnit.SECONDS); } catch (Exception e) { res[0] = chain(e); }
        });
        tg.start();
        until(() -> tg.getState() == Thread.State.TIMED_WAITING);
        t.cancel(false);
        tg.join();
        show("timed waiter on cancel", () -> res[0]);
    }

    /** Dependents are plain CompletableFutures, whatever the source's class. */
    static class Sub<T> extends CompletableFuture<T> {
        @Override public String toString() { return "Sub:" + super.toString(); }
    }

    static void subclass() {
        Sub<String> s = new Sub<>();
        show("sub toString", () -> s);
        show("sub dependent class", () -> s.thenApply(x -> x).getClass().getName());
        show("sub toCompletableFuture", () -> s.toCompletableFuture() == s);
        show("sub handle class", () -> s.handle((x, y) -> x).getClass().getName());
        show("sub exceptionally class", () -> s.exceptionally(x -> "z").getClass().getName());
        show("is stage", () -> (Object) s instanceof CompletionStage);
        show("is future", () -> (Object) s instanceof Future);
    }

    public static void main(String[] args) throws Exception {
        states();
        failures();
        cancels();
        obtrudes();
        interrupts();
        subclass();
    }
}
