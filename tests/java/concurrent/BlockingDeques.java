package concurrent;

import java.util.*;
import java.util.concurrent.*;

/** LinkedBlockingDeque: both ends, capacity errors, blocking at each end, iterators. */
public class BlockingDeques {
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

    static void arguments() {
        show("capacity zero", () -> new LinkedBlockingDeque<String>(0));
        show("default capacity", () -> new LinkedBlockingDeque<String>().remainingCapacity());
        show("init", () -> new LinkedBlockingDeque<>(Arrays.asList("a", "b")));
        show("init null", () -> new LinkedBlockingDeque<String>((Collection<String>) null));
        show("init null element", () -> new LinkedBlockingDeque<>(Arrays.asList("a", null)));
    }

    static void ends() throws Exception {
        LinkedBlockingDeque<String> d = new LinkedBlockingDeque<>(3);
        show("empty", () -> d);
        show("peekFirst", d::peekFirst);
        show("peekLast", d::peekLast);
        show("pollFirst", d::pollFirst);
        show("pollLast", d::pollLast);
        show("getFirst", d::getFirst);
        show("getLast", d::getLast);
        show("removeFirst", d::removeFirst);
        show("removeLast", d::removeLast);
        show("pop", d::pop);
        show("element", d::element);
        show("remove", () -> d.remove());
        show("timed pollFirst", () -> d.pollFirst(1, TimeUnit.MILLISECONDS));
        show("timed pollLast", () -> d.pollLast(1, TimeUnit.MILLISECONDS));
        show("addFirst null", () -> { d.addFirst(null); return "no"; });
        show("addLast null", () -> { d.addLast(null); return "no"; });
        show("offerFirst null", () -> d.offerFirst(null));
        show("offerLast null", () -> d.offerLast(null));
        show("putFirst null", () -> { d.putFirst(null); return "no"; });
        show("putLast null", () -> { d.putLast(null); return "no"; });
        show("timed offerFirst null", () -> d.offerFirst(null, 1, TimeUnit.SECONDS));
        show("timed offerLast null", () -> d.offerLast(null, 1, TimeUnit.SECONDS));
        show("push null", () -> { d.push(null); return "no"; });
        d.addFirst("b");
        show("addFirst", () -> d);
        d.addLast("c");
        show("addLast", () -> d);
        d.push("a");
        show("push", () -> d);
        show("remaining", d::remainingCapacity);
        show("addFirst full", () -> { d.addFirst("x"); return "no"; });
        show("addLast full", () -> { d.addLast("x"); return "no"; });
        show("add full", () -> d.add("x"));
        show("push full", () -> { d.push("x"); return "no"; });
        show("offerFirst full", () -> d.offerFirst("x"));
        show("offerLast full", () -> d.offerLast("x"));
        show("offer full", () -> d.offer("x"));
        show("timed offerFirst full", () -> d.offerFirst("x", 1, TimeUnit.MILLISECONDS));
        show("timed offerLast full", () -> d.offerLast("x", 1, TimeUnit.MILLISECONDS));
        show("peek", d::peek);
        show("peekFirst 2", d::peekFirst);
        show("peekLast 2", d::peekLast);
        show("getFirst 2", d::getFirst);
        show("getLast 2", d::getLast);
        show("element 2", d::element);
        show("takeLast", d::takeLast);
        show("takeFirst", d::takeFirst);
        show("take", d::take);
        d.putLast("l");
        d.putFirst("f");
        d.put("m");
        show("puts", () -> d);
        show("poll", d::poll);
        show("timed poll", () -> d.poll(1, TimeUnit.MILLISECONDS));
        show("timed pollLast 2", () -> d.pollLast(1, TimeUnit.MILLISECONDS));
        show("empty again", d::isEmpty);
    }

    static void occurrences() {
        LinkedBlockingDeque<String> d = new LinkedBlockingDeque<>(Arrays.asList("a", "b", "a", "c", "a"));
        show("removeFirstOccurrence", () -> d.removeFirstOccurrence("a"));
        show("then", () -> d);
        show("removeLastOccurrence", () -> d.removeLastOccurrence("a"));
        show("then 2", () -> d);
        show("remove obj is first", () -> d.remove("zz"));
        show("first null", () -> d.removeFirstOccurrence(null));
        show("last null", () -> d.removeLastOccurrence(null));
        show("remove null", () -> d.remove(null));
        show("contains null", () -> d.contains(null));
        show("contains", () -> d.contains("c"));
        show("toArray", () -> Arrays.toString(d.toArray()));
        show("toArray typed", () -> Arrays.toString(d.toArray(new String[4])));
        show("toArray null", () -> d.toArray((String[]) null));
        List<String> out = new ArrayList<>();
        show("drain self", () -> d.drainTo(d));
        show("drain null", () -> d.drainTo(null));
        show("drain none", () -> d.drainTo(out, 0));
        show("drain 1", () -> d.drainTo(out, 1));
        show("out", () -> out);
        show("drain", () -> d.drainTo(out));
        show("out 2", () -> out);
        d.addAll(Arrays.asList("a", "b", "c"));
        show("drain refused midway", () -> drainFailing(d));
    }

    static void iterators() {
        LinkedBlockingDeque<String> d = new LinkedBlockingDeque<>(Arrays.asList("a", "b", "c", "d"));
        List<String> asc = new ArrayList<>();
        for (String s : d) asc.add(s);
        show("ascending", () -> asc);
        List<String> desc = new ArrayList<>();
        for (Iterator<String> i = d.descendingIterator(); i.hasNext();) desc.add(i.next());
        show("descending", () -> desc);
        Iterator<String> it = d.iterator();
        show("remove before next", () -> { it.remove(); return "no"; });
        it.next();
        it.remove();
        show("removed head", () -> d);
        show("remove twice", () -> { it.remove(); return "no"; });
        Iterator<String> di = d.descendingIterator();
        di.next();
        di.remove();
        show("removed tail", () -> d);
        Iterator<String> w = d.iterator();
        d.add("later");
        show("no CME", () -> { List<String> l = new ArrayList<>(); while (w.hasNext()) l.add(w.next()); return l.contains("b"); });
        show("past end", w::next);
        d.clear();
        show("clear", () -> d);
        show("empty descending", () -> d.descendingIterator().hasNext());
        show("empty descending next", () -> d.descendingIterator().next());
    }

    static void blocking() throws Exception {
        LinkedBlockingDeque<String> d = new LinkedBlockingDeque<>(1);
        String[] res = new String[3];
        Thread putter = new Thread(() -> {
            try { d.putFirst("p1"); d.putFirst("p2"); res[0] = "both in"; }
            catch (InterruptedException e) { res[0] = "interrupted"; }
        });
        putter.start();
        until(() -> putter.getState() == Thread.State.WAITING);
        show("taken at the far end", d::takeLast);
        putter.join();
        show("putter", () -> res[0]);
        show("left", () -> d);
        d.clear();
        Thread taker = new Thread(() -> {
            try { res[1] = d.takeLast(); } catch (InterruptedException e) { res[1] = "interrupted"; }
        });
        taker.start();
        until(() -> taker.getState() == Thread.State.WAITING);
        d.addFirst("v");
        taker.join();
        show("takeLast waited", () -> res[1]);
        Thread stuck = new Thread(() -> {
            try { res[2] = d.takeFirst(); }
            catch (InterruptedException e) { res[2] = n(e) + " flag=" + Thread.currentThread().isInterrupted(); }
        });
        stuck.start();
        until(() -> stuck.getState() == Thread.State.WAITING);
        stuck.interrupt();
        stuck.join();
        show("interrupted", () -> res[2]);
        d.add("x");
        Thread.currentThread().interrupt();
        show("takeFirst flagged, element ready", d::takeFirst);
        Thread.interrupted();
        d.clear();
        Thread.currentThread().interrupt();
        show("putFirst flagged, room", () -> { d.putFirst("q"); return d; });
        show("flag kept by uninterruptible put", Thread::interrupted);
        d.clear();
        Thread.currentThread().interrupt();
        show("timed offerFirst flagged", () -> d.offerFirst("q", 0, TimeUnit.SECONDS));
        Thread.interrupted();
    }

    public static void main(String[] args) throws Exception {
        arguments();
        ends();
        occurrences();
        iterators();
        blocking();
    }
}
