package concurrent;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

/** ConcurrentLinkedQueue and ConcurrentLinkedDeque: nulls, FIFO, weak iterators, thread totals. */
public class ConcurrentLinked {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    static void queue() {
        ConcurrentLinkedQueue<String> q = new ConcurrentLinkedQueue<>();
        show("empty", () -> q);
        show("isEmpty", q::isEmpty);
        show("poll empty", q::poll);
        show("peek empty", q::peek);
        show("element empty", q::element);
        show("remove empty", () -> q.remove());
        show("add null", () -> q.add(null));
        show("offer null", () -> q.offer(null));
        show("contains null", () -> q.contains(null));
        show("remove null", () -> q.remove(null));
        show("null collection", () -> new ConcurrentLinkedQueue<String>(null));
        show("null element", () -> new ConcurrentLinkedQueue<>(Arrays.asList("a", null)));
        show("collection", () -> new ConcurrentLinkedQueue<>(Arrays.asList("a", "b")));
        show("addAll self", () -> q.addAll(q));
        show("addAll null element", () -> q.addAll(Arrays.asList("x", null)));
        show("addAll atomic", () -> q);
        show("add", () -> q.add("a"));
        show("offer", () -> q.offer("b"));
        show("addAll", () -> q.addAll(Arrays.asList("c", "d")));
        show("addAll empty", () -> q.addAll(new ArrayList<>()));
        show("order", () -> q);
        show("size", q::size);
        show("peek", q::peek);
        show("element", q::element);
        show("contains", () -> q.contains("c"));
        show("remove obj", () -> q.remove("b"));
        show("remove absent", () -> q.remove("b"));
        show("toArray", () -> Arrays.toString(q.toArray()));
        show("toArray big", () -> Arrays.toString(q.toArray(new String[4])));
        show("toArray null", () -> q.toArray((String[]) null));
        Iterator<String> it = q.iterator();
        show("remove before next", () -> { it.remove(); return "no"; });
        it.next();
        it.remove();
        show("iterator remove", () -> q);
        show("remove twice", () -> { it.remove(); return "no"; });
        Iterator<String> w = q.iterator();
        q.add("later");
        List<String> seen = new ArrayList<>();
        while (w.hasNext()) seen.add(w.next());
        show("sees later add", () -> seen);
        show("past end", w::next);
        show("poll", q::poll);
        show("remove()", () -> q.remove());
        q.clear();
        show("clear", q::size);
    }

    static void deque() {
        ConcurrentLinkedDeque<String> d = new ConcurrentLinkedDeque<>();
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
        show("addFirst null", () -> { d.addFirst(null); return "no"; });
        show("addLast null", () -> { d.addLast(null); return "no"; });
        show("offerFirst null", () -> d.offerFirst(null));
        show("offerLast null", () -> d.offerLast(null));
        show("push null", () -> { d.push(null); return "no"; });
        show("add null", () -> d.add(null));
        show("contains null", () -> d.contains(null));
        show("remove null", () -> d.remove(null));
        show("first null", () -> d.removeFirstOccurrence(null));
        show("last null", () -> d.removeLastOccurrence(null));
        show("null collection", () -> new ConcurrentLinkedDeque<String>(null));
        show("null element", () -> new ConcurrentLinkedDeque<>(Arrays.asList("a", null)));
        show("addAll self", () -> d.addAll(d));
        show("addAll null element", () -> d.addAll(Arrays.asList("x", null)));
        show("addAll atomic", () -> d);
        d.addFirst("b");
        d.addLast("c");
        d.push("a");
        show("offerFirst", () -> d.offerFirst("0"));
        show("offerLast", () -> d.offerLast("d"));
        show("offer", () -> d.offer("e"));
        show("add", () -> d.add("f"));
        show("order", () -> d);
        show("size", d::size);
        show("peek", d::peek);
        show("peekLast 2", d::peekLast);
        show("getFirst 2", d::getFirst);
        show("getLast 2", d::getLast);
        show("pollLast 2", d::pollLast);
        show("pollFirst 2", d::pollFirst);
        show("pop 2", d::pop);
        show("removeLast 2", d::removeLast);
        show("removeFirst 2", d::removeFirst);
        show("poll", d::poll);
        show("left", () -> d);
        ConcurrentLinkedDeque<String> o = new ConcurrentLinkedDeque<>(Arrays.asList("a", "b", "a", "c", "a"));
        show("removeFirstOccurrence", () -> o.removeFirstOccurrence("a"));
        show("removeLastOccurrence", () -> o.removeLastOccurrence("a"));
        show("then", () -> o);
        show("remove obj", () -> o.remove("a"));
        show("remove absent", () -> o.remove("zz"));
        show("then 2", () -> o);
        List<String> desc = new ArrayList<>();
        ConcurrentLinkedDeque<String> i = new ConcurrentLinkedDeque<>(Arrays.asList("a", "b", "c", "d"));
        for (Iterator<String> x = i.descendingIterator(); x.hasNext();) desc.add(x.next());
        show("descending", () -> desc);
        show("toArray", () -> Arrays.toString(i.toArray()));
        show("toArray typed", () -> Arrays.toString(i.toArray(new String[5])));
        Iterator<String> it = i.iterator();
        show("remove before next", () -> { it.remove(); return "no"; });
        it.next();
        it.remove();
        show("iterator remove", () -> i);
        show("remove twice", () -> { it.remove(); return "no"; });
        Iterator<String> di = i.descendingIterator();
        di.next();
        di.remove();
        show("descending remove", () -> i);
        Iterator<String> w = i.iterator();
        i.addLast("later");
        List<String> seen = new ArrayList<>();
        while (w.hasNext()) seen.add(w.next());
        show("sees later add", () -> seen);
        show("past end", w::next);
        i.clear();
        show("clear", i::isEmpty);
    }

    static void threads() throws Exception {
        ConcurrentLinkedQueue<Integer> q = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedDeque<Integer> d = new ConcurrentLinkedDeque<>();
        int workers = 3, each = 3000;
        CountDownLatch start = new CountDownLatch(1);
        long[] sums = new long[workers];
        Thread[] ts = new Thread[workers * 2];
        for (int w = 0; w < workers; w++) {
            int id = w;
            ts[w] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < each; i++) {
                    q.add(i);
                    if (i % 2 == 0) d.addFirst(i); else d.addLast(i);
                }
            });
            ts[workers + w] = new Thread(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                int got = 0;
                while (got < each) {
                    Integer a = q.poll();
                    if (a != null) { sums[id] += a; got++; }
                }
            });
        }
        for (Thread t : ts) t.start();
        start.countDown();
        for (Thread t : ts) t.join();
        long total = sums[0] + sums[1] + sums[2];
        show("polled total", () -> total);
        show("queue empty", q::isEmpty);
        show("deque size", d::size);
        long sum = 0;
        for (int v : d) sum += v;
        long dtotal = sum;
        show("deque total", () -> dtotal);
    }

    public static void main(String[] args) throws Exception {
        queue();
        deque();
        threads();
    }
}
