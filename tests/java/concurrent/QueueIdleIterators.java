// VMARGS: -Xmx3m
package concurrent;

import java.util.*;
import java.util.concurrent.*;

/**
 * An iterator left idle must not hold on to what passes through the queue after it: on a 3 MB heap each
 * loop here would run out of memory if it did. The second part is the promise a weak iterator keeps while
 * the queue changes: no element twice, and every element that stayed throughout is seen.
 */
public class QueueIdleIterators {
    static final int CYCLES = 60000;

    /** Traffic at the two ends, then in the middle, past one idle iterator of each direction. */
    static void traffic(Deque<byte[]> q) {
        q.offer(new byte[10]);
        Iterator<byte[]> idle = q.iterator();
        Iterator<byte[]> idleBack = q.descendingIterator();
        for (int i = 0; i < CYCLES; i++) {
            q.offer(new byte[10]);
            q.poll();
        }
        System.out.println(q.getClass().getSimpleName() + " head traffic: " + q.size());
        Iterator<byte[]> idleTail = q.descendingIterator();
        for (int i = 0; i < CYCLES; i++) {
            q.offerFirst(new byte[10]);
            q.pollLast();
        }
        System.out.println(q.getClass().getSimpleName() + " other way: " + q.size());
        byte[] middle = new byte[10];
        q.offer(middle);
        for (int i = 0; i < CYCLES; i++) {
            byte[] next = new byte[10];
            q.offer(next);
            q.remove(middle);
            middle = next;
        }
        System.out.println(q.getClass().getSimpleName() + " middle traffic: " + q.size());
        System.out.println(idle.hasNext() + " " + idleBack.hasNext() + " " + idleTail.hasNext());
    }

    static void queueTraffic(Queue<byte[]> q) {
        q.offer(new byte[10]);
        Iterator<byte[]> idle = q.iterator();
        for (int i = 0; i < CYCLES; i++) {
            q.offer(new byte[10]);
            q.poll();
        }
        System.out.println(q.getClass().getSimpleName() + " traffic: " + q.size() + " " + idle.hasNext());
    }

    static int promise(Collection<Integer> q, Random r, boolean back) {
        int broken = 0;
        for (int round = 0; round < 40; round++) {
            q.clear();
            int id = 0;
            Set<Integer> stayed = new HashSet<>();
            for (int n = r.nextInt(12); n > 0; n--) {
                q.add(id);
                stayed.add(id++);
            }
            Iterator<Integer> it = back ? ((Deque<Integer>) q).descendingIterator() : q.iterator();
            Set<Integer> seen = new HashSet<>();
            for (int step = 0; step < 200; step++) {
                switch (r.nextInt(8)) {
                    case 0: case 1: case 2:
                        if (!it.hasNext()) {
                            step = 200;
                            break;
                        }
                        Integer x = it.next();
                        if (!seen.add(x)) broken++;
                        if (r.nextInt(4) == 0) {
                            it.remove();
                            stayed.remove(x);
                        }
                        break;
                    case 3:
                        q.add(id++);
                        break;
                    case 4:
                        if (q instanceof Deque) ((Deque<Integer>) q).addFirst(id++);
                        break;
                    case 5:
                        stayed.remove(q instanceof Deque && r.nextBoolean() ? ((Deque<Integer>) q).pollLast() : ((Queue<Integer>) q).poll());
                        break;
                    default:
                        Integer v = r.nextInt(id + 1);
                        if (q.remove(v)) stayed.remove(v);
                }
            }
            while (it.hasNext()) {
                if (!seen.add(it.next())) broken++;
            }
            for (Integer s : stayed) {
                if (!seen.contains(s)) broken++;
            }
        }
        return broken;
    }

    /** The element it holds was at the iterator's own end when it left: it starts again there. */
    static void startsAgain() {
        Deque<String> d = new LinkedBlockingDeque<>();
        String name = d.getClass().getSimpleName();
        d.add("a");
        Iterator<String> back = d.descendingIterator();
        d.pollLast();
        d.addLast("b");
        System.out.println(name + " backward, then newer at its end: " + walk(back));
        d.clear();
        d.add("a");
        d.add("c");
        Iterator<String> forward = d.iterator();
        d.pollFirst();
        d.addFirst("b");
        System.out.println(name + " forward, then newer at its end: " + walk(forward));
        d.clear();
    }

    static String walk(Iterator<String> it) {
        StringBuilder out = new StringBuilder();
        while (it.hasNext()) out.append(it.next()).append(' ');
        return out.toString();
    }

    public static void main(String[] args) {
        startsAgain();
        traffic(new ConcurrentLinkedDeque<>());
        traffic(new LinkedBlockingDeque<>());
        queueTraffic(new ConcurrentLinkedQueue<>());
        queueTraffic(new LinkedBlockingQueue<>());
        queueTraffic(new LinkedTransferQueue<>());

        Random r = new Random(11);
        int broken = 0;
        for (int seed = 0; seed < 25; seed++) {
            broken += promise(new ConcurrentLinkedQueue<>(), r, false);
            broken += promise(new LinkedBlockingQueue<>(), r, false);
            broken += promise(new LinkedTransferQueue<>(), r, false);
            broken += promise(new ConcurrentLinkedDeque<>(), r, false);
            broken += promise(new LinkedBlockingDeque<>(), r, false);
            broken += promise(new ConcurrentLinkedDeque<>(), r, true);
            broken += promise(new LinkedBlockingDeque<>(), r, true);
        }
        System.out.println("broken promises: " + broken);
    }
}
