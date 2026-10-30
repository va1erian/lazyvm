package concurrent;

import java.util.*;
import java.util.concurrent.*;

/** PriorityBlockingQueue and DelayQueue. Iterator and toString order is never printed. */
public class PriorityAndDelayQueues {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    /** Takes one element, then refuses the rest: which elements survive the failure? */
    static <T> String drainFailing(BlockingQueue<T> q) {
        List<T> sink = new ArrayList<T>() {
            @Override public boolean add(T t) {
                if (!isEmpty()) throw new IllegalStateException("sink");
                return super.add(t);
            }
        };
        try { return "returned " + q.drainTo(sink); }
        catch (IllegalStateException e) { return n(e) + " left=" + q + " got=" + new ArrayList<>(sink); }
    }

    interface Cond { boolean ok() throws Exception; }

    static void until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("stuck");
            Thread.sleep(1);
        }
    }

    static void priorityArguments() {
        show("capacity zero", () -> new PriorityBlockingQueue<String>(0));
        show("capacity zero cmp", () -> new PriorityBlockingQueue<String>(0, null));
        show("null collection", () -> new PriorityBlockingQueue<String>(null));
        show("collection null element", () -> new PriorityBlockingQueue<>(Arrays.asList("a", null)));
        show("capacity one grows", () -> {
            PriorityBlockingQueue<Integer> q = new PriorityBlockingQueue<>(1);
            for (int i = 0; i < 100; i++) q.add(i);
            return q.size();
        });
        show("default comparator", () -> new PriorityBlockingQueue<String>().comparator());
        Comparator<String> rev = Comparator.reverseOrder();
        show("given comparator", () -> new PriorityBlockingQueue<String>(1, rev).comparator() == rev);
        show("sorted source comparator kept", () -> {
            TreeSet<String> s = new TreeSet<>(rev);
            s.add("a");
            return new PriorityBlockingQueue<String>(s).comparator() == rev;
        });
        show("queue source comparator kept", () ->
            new PriorityBlockingQueue<String>(new PriorityBlockingQueue<String>(1, rev)).comparator() == rev);
        show("plain source no comparator", () -> new PriorityBlockingQueue<>(Arrays.asList("b", "a")).comparator());
    }

    static void priorityOrder() throws Exception {
        PriorityBlockingQueue<Integer> q = new PriorityBlockingQueue<>();
        show("empty", () -> q);
        show("poll empty", q::poll);
        show("peek empty", q::peek);
        show("timed poll empty", () -> q.poll(1, TimeUnit.MILLISECONDS));
        show("element empty", q::element);
        show("remove empty", () -> q.remove());
        show("remaining", q::remainingCapacity);
        show("add null", () -> q.add(null));
        show("offer null", () -> q.offer(null));
        show("put null", () -> { q.put(null); return "no"; });
        show("timed offer", () -> q.offer(4, 1, TimeUnit.SECONDS));
        q.clear();
        for (int v : new int[] {5, 1, 4, 2, 3, 2}) q.put(v);
        show("one element toString", () -> new PriorityBlockingQueue<>(Arrays.asList("only")));
        show("size", q::size);
        show("peek", q::peek);
        show("contains", () -> q.contains(3));
        show("contains null", () -> q.contains(null));
        show("remove null", () -> q.remove((Object) null));
        show("remove value", () -> q.remove((Object) 4));
        show("sorted toArray length", () -> q.toArray().length);
        show("toArray typed length", () -> q.toArray(new Integer[0]).length);
        show("iterator contents", () -> { List<Integer> l = new ArrayList<>(); for (int i : q) l.add(i); Collections.sort(l); return l; });
        List<Integer> out = new ArrayList<>();
        while (!q.isEmpty()) out.add(q.take());
        show("take order", () -> out);
        q.addAll(Arrays.asList(3, 1, 2));
        List<Integer> d = new ArrayList<>();
        show("drain null", () -> q.drainTo(null));
        show("drain self", () -> q.drainTo(q));
        show("drain none", () -> q.drainTo(d, 0));
        show("drain 2", () -> q.drainTo(d, 2));
        show("drain order", () -> d);
        show("drain rest", () -> q.drainTo(d));
        show("drain order 2", () -> d);
        q.addAll(Arrays.asList(1, 2, 3));
        show("drain refused midway", () -> {
            String r = drainFailing(q);
            return r.substring(0, r.indexOf(" left=")) + " left " + q.size() + " head " + q.peek();
        });
        q.clear();
        q.add(7);
        Thread.currentThread().interrupt();
        show("take flagged, element ready", q::take);
        show("cleared by take", () -> Thread.currentThread().isInterrupted() + " " + q.size());
        Thread.currentThread().interrupt();
        show("timed poll flagged, element ready", () -> q.poll(1, TimeUnit.SECONDS));
        Thread.interrupted();
        q.clear();
        Thread.currentThread().interrupt();
        show("put flagged", () -> { q.put(1); return q.size(); });
        show("timed offer flagged", () -> q.offer(2, 1, TimeUnit.SECONDS));
        show("flag kept by put and offer", Thread::interrupted);
        q.clear();
        PriorityBlockingQueue<String> rev = new PriorityBlockingQueue<>(4, Comparator.reverseOrder());
        rev.addAll(Arrays.asList("a", "c", "b"));
        show("comparator order", () -> rev.take() + rev.take() + rev.take());
        PriorityBlockingQueue<Object> raw = new PriorityBlockingQueue<>();
        show("first non-comparable", () -> raw.add(new Object()));
        show("after failure", raw::size);
        raw.clear();
        raw.add("s");
        show("second mismatch", () -> raw.add(1));
        show("after mismatch", raw::size);
        PriorityBlockingQueue<Integer> it = new PriorityBlockingQueue<>(Arrays.asList(1, 2, 3));
        Iterator<Integer> i = it.iterator();
        show("remove before next", () -> { i.remove(); return "no"; });
        Integer first = i.next();
        i.remove();
        show("iterator remove", () -> it.contains(first));
        it.add(9);
        int count = 0;
        while (i.hasNext()) { i.next(); count++; }
        int c = count;
        show("iterator is a snapshot", () -> c);
        show("past end", i::next);
        show("removeIf", () -> it.removeIf(v -> v == 9));
        Thread taker = new Thread(() -> { try { it.clear(); it.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        taker.start();
        until(() -> taker.getState() == Thread.State.WAITING);
        it.add(1);
        taker.join();
        show("taker released", it::size);
        Thread.currentThread().interrupt();
        show("take flagged", it::take);
    }

    static class Item implements Delayed {
        final String name; final long due;
        Item(String name, long delayMs) { this.name = name; this.due = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs); }
        @Override public long getDelay(TimeUnit u) { return u.convert(due - System.nanoTime(), TimeUnit.NANOSECONDS); }
        @Override public int compareTo(Delayed o) { return Long.compare(due, ((Item) o).due); }
        @Override public String toString() { return name; }
    }

    static void delayQueue() throws Exception {
        DelayQueue<Item> q = new DelayQueue<>();
        show("empty", () -> q);
        show("poll empty", q::poll);
        show("peek empty", q::peek);
        show("timed poll empty", () -> q.poll(1, TimeUnit.MILLISECONDS));
        show("remaining", q::remainingCapacity);
        show("element empty", q::element);
        show("remove empty", () -> q.remove());
        show("add null", () -> q.add(null));
        show("offer null", () -> q.offer(null));
        show("put null", () -> { q.put(null); return "no"; });
        show("null collection", () -> new DelayQueue<Item>(null));
        show("collection", () -> new DelayQueue<>(Arrays.asList(new Item("one", 0))));
        Item far = new Item("far", 3_600_000);
        Item nearer = new Item("nearer", 1_800_000);
        q.add(far);
        q.put(nearer);
        show("offer timed", () -> q.offer(new Item("t", 3_700_000), 1, TimeUnit.SECONDS));
        show("size counts unexpired", q::size);
        show("poll unexpired", q::poll);
        show("peek shows unexpired head", q::peek);
        show("element", q::element);
        show("timed poll unexpired", () -> q.poll(1, TimeUnit.MILLISECONDS));
        List<Item> sink = new ArrayList<>();
        show("drain unexpired", () -> q.drainTo(sink));
        show("remove()", () -> q.remove());
        show("drain null", () -> q.drainTo(null));
        show("drain self", () -> q.drainTo(q));
        show("contains", () -> q.contains(far));
        show("remove obj", () -> q.remove(far));
        show("toArray length", () -> q.toArray().length);
        show("iterator sees unexpired", () -> q.iterator().hasNext());
        q.clear();
        show("clear", q::size);
        Item past = new Item("past", -1000);
        Item past2 = new Item("past2", -500);
        Item future = new Item("future", 3_600_000);
        q.add(future);
        q.add(past2);
        q.add(past);
        show("peek expired head", q::peek);
        show("drain 1", () -> q.drainTo(sink, 1));
        show("sink", () -> sink);
        show("drain expired only", () -> q.drainTo(sink));
        show("sink 2", () -> sink);
        show("size", q::size);
        q.clear();
        q.add(new Item("e1", -1000));
        q.add(new Item("e2", -500));
        q.add(new Item("e3", -100));
        show("drain refused midway", () -> {
            String r = drainFailing(q);
            return r.substring(0, r.indexOf(" left=")) + " left " + q.size() + " head " + q.peek();
        });
        q.clear();
        q.add(new Item("ready", -1000));
        Thread.currentThread().interrupt();
        show("take flagged, element ready", q::take);
        show("cleared by take", () -> Thread.currentThread().isInterrupted() + " " + q.size());
        q.clear();
        Thread.currentThread().interrupt();
        show("put flagged", () -> { q.put(new Item("p", -1)); return q.size(); });
        show("timed offer flagged", () -> q.offer(new Item("o", -1), 1, TimeUnit.SECONDS));
        show("flag kept by put and offer", Thread::interrupted);
        q.clear();
        q.add(new Item("soon", 30));
        show("take waits", () -> q.take().name);
        q.add(new Item("soon2", 30));
        show("timed poll waits", () -> q.poll(30, TimeUnit.SECONDS).name);
        Thread t = new Thread(() -> { try { q.take(); } catch (InterruptedException e) { } });
        t.start();
        until(() -> t.getState() == Thread.State.WAITING);
        show("taker waits on empty", () -> t.getState());
        q.add(new Item("wake", 0));
        t.join();
        show("taken", q::size);
        Thread.currentThread().interrupt();
        show("take flagged", q::take);
        q.add(new Item("first", 3_600_000));
        Thread later = new Thread(() -> { try { q.take(); } catch (InterruptedException e) { } });
        later.start();
        until(() -> later.getState() == Thread.State.TIMED_WAITING);
        show("taker sleeps until head due", later::getState);
        q.add(new Item("nearer head", 0));
        later.join();
        show("earlier head wakes taker", () -> q.peek());
    }

    public static void main(String[] args) throws Exception {
        priorityArguments();
        priorityOrder();
        delayQueue();
    }
}
