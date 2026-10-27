package util;

import java.util.*;
import java.util.function.*;

/** ArrayDeque and LinkedList as Deque, Stack-style and Queue-style use, null handling, iteration. */
public class Deques {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static void run(String name, Deque<Integer> d) {
        System.out.println("== " + name);
        d.addLast(1); d.addLast(2); d.addFirst(0); d.offerFirst(-1); d.offerLast(3); d.add(4); d.offer(5); d.push(-2);
        System.out.println(d + " " + d.size() + " " + d.peekFirst() + " " + d.peekLast() + " " + d.getFirst() + " " + d.getLast() + " " + d.peek() + " " + d.element());
        System.out.println(d.pop() + " " + d.poll() + " " + d.pollLast() + " " + d.remove() + " " + d.removeLast() + " " + d.removeFirst() + " " + d);
        d.addAll(Arrays.asList(7, 8, 7, 9, 7));
        System.out.println(d + " " + d.contains(8) + " " + d.contains(77));
        System.out.println(d.removeFirstOccurrence(7) + " " + d + " " + d.removeLastOccurrence(7) + " " + d + " " + d.remove((Object) 7) + " " + d + " " + d.remove((Object) 70));
        StringBuilder sb = new StringBuilder();
        for (Iterator<Integer> it = d.descendingIterator(); it.hasNext();) sb.append(it.next()).append(',');
        for (Integer x : d) sb.append(x).append(';');
        System.out.println(sb);
        Object[] arr = d.toArray();
        Integer[] arr2 = d.toArray(new Integer[d.size() + 2]);
        System.out.println(Arrays.toString(arr) + " " + Arrays.toString(arr2));
        d.clear();
        System.out.println(d + " " + d.isEmpty() + " " + d.size() + " " + d.peek() + " " + d.poll() + " " + d.pollFirst() + " " + d.pollLast() + " " + d.peekFirst() + " " + d.peekLast());
        t(name + " getFirst", () -> d.getFirst());
        t(name + " getLast", () -> d.getLast());
        t(name + " removeFirst", () -> d.removeFirst());
        t(name + " removeLast", () -> d.removeLast());
        t(name + " element", () -> d.element());
        t(name + " remove", () -> d.remove());
        t(name + " pop", () -> d.pop());
        for (int i = 0; i < 40; i++) { d.addLast(i); d.addFirst(-i); }
        for (int i = 0; i < 30; i++) { d.pollFirst(); d.pollLast(); }
        System.out.println(d + " " + d.size());
        for (int i = 0; i < 100; i++) d.addFirst(i);
        System.out.println(d.size() + " " + d.peekFirst() + " " + d.peekLast());
        Iterator<Integer> it = d.iterator();
        int removed = 0;
        while (it.hasNext()) { int x = it.next(); if (x % 3 != 0) { it.remove(); removed++; } }
        System.out.println(removed + " " + d.size() + " " + d);
        Iterator<Integer> di = d.descendingIterator();
        while (di.hasNext()) { int x = di.next(); if (x % 2 == 0) di.remove(); }
        System.out.println(d);
        t(name + " iterator remove twice", () -> { Iterator<Integer> i = d.iterator(); i.next(); i.remove(); i.remove(); return 0; });
        t(name + " iterator remove first", () -> { d.iterator().remove(); return 0; });
        t(name + " iterator end", () -> { Iterator<Integer> i = d.iterator(); while (i.hasNext()) i.next(); return i.next(); });
        t(name + " removeIf", () -> d.removeIf(x -> x > 40) + " " + d);
        t(name + " forEach", () -> { StringBuilder s = new StringBuilder(); d.forEach(x -> s.append(x).append(' ')); return s; });
    }

    public static void main(String[] args) {
        run("ArrayDeque", new ArrayDeque<Integer>());
        run("LinkedList", new LinkedList<Integer>());
        ArrayDeque<String> n = new ArrayDeque<>();
        t("add null", () -> n.add(null));
        t("addFirst null", () -> { n.addFirst(null); return 0; });
        t("offer null", () -> n.offerLast(null));
        t("push null", () -> { n.push(null); return 0; });
        t("contains null", () -> n.contains(null) + " " + n.remove(null) + " " + n.removeFirstOccurrence(null) + " " + n.removeLastOccurrence(null));
        t("addAll null element", () -> n.addAll(Arrays.asList("a", null, "b")) + " " + n);
        t("ctor null", () -> new ArrayDeque<String>((Collection<String>) null));
        t("ctor neg", () -> new ArrayDeque<String>(-5).size());
        t("ctor coll", () -> new ArrayDeque<Integer>(Arrays.asList(3, 2, 1)));
        t("ctor big", () -> { ArrayDeque<Integer> d = new ArrayDeque<>(100); for (int i = 0; i < 300; i++) d.add(i); return d.size() + " " + d.peekLast(); });
        t("clone", () -> { ArrayDeque<Integer> d = new ArrayDeque<>(Arrays.asList(1, 2)); ArrayDeque<Integer> c = d.clone(); c.add(3); return d + " " + c; });
        t("iterator CME add", () -> { ArrayDeque<Integer> d = new ArrayDeque<>(Arrays.asList(1, 2, 3)); for (Integer x : d) d.addLast(9); return d; });
        t("iterator CME addFirst", () -> { ArrayDeque<Integer> d = new ArrayDeque<>(Arrays.asList(1, 2, 3)); Iterator<Integer> i = d.iterator(); d.addFirst(0); return i.next(); });
        t("iterator poll", () -> { ArrayDeque<Integer> d = new ArrayDeque<>(Arrays.asList(1, 2, 3)); Iterator<Integer> i = d.iterator(); d.pollFirst(); return i.next(); });
        t("iterator pollLast", () -> { ArrayDeque<Integer> d = new ArrayDeque<>(Arrays.asList(1, 2, 3)); Iterator<Integer> i = d.iterator(); d.pollLast(); return i.next() + " " + i.next() + " " + i.hasNext(); });
        t("descending CME", () -> { ArrayDeque<Integer> d = new ArrayDeque<>(Arrays.asList(1, 2, 3)); Iterator<Integer> i = d.descendingIterator(); d.addFirst(0); return i.next(); });
        t("iterator after clear", () -> { ArrayDeque<Integer> d = new ArrayDeque<>(Arrays.asList(1, 2, 3)); Iterator<Integer> i = d.iterator(); i.next(); d.clear(); return i.hasNext(); });
        String[] changes = {"addFirst", "addLast", "pollFirst", "pollLast", "remove index 1", "remove index 2", "remove index 3 (tie)", "remove index 4", "remove index 5", "clear"};
        for (String change : changes) {
            StringBuilder sb = new StringBuilder("iterator vs " + change + ":");
            for (int desc = 0; desc < 2; desc++) {
                for (int pos = 0; pos <= 2; pos++) {
                    ArrayDeque<Integer> d = new ArrayDeque<>(Arrays.asList(0, 1, 2, 3, 4, 5, 6));
                    Iterator<Integer> it = desc == 0 ? d.iterator() : d.descendingIterator();
                    sb.append(desc == 0 ? " " : " desc ");
                    for (int i = 0; i < pos; i++) sb.append(it.next());
                    if (change.equals("addFirst")) d.addFirst(9); else if (change.equals("addLast")) d.addLast(9);
                    else if (change.equals("pollFirst")) d.pollFirst(); else if (change.equals("pollLast")) d.pollLast();
                    else if (change.equals("clear")) d.clear(); else d.removeFirstOccurrence(change.charAt(13) - '0');
                    sb.append('|');
                    try { while (it.hasNext()) sb.append(it.next()); } catch (ConcurrentModificationException e) { sb.append("CME"); }
                }
            }
            System.out.println(sb);
        }
        t("forEachRemaining ignores additions", () -> { ArrayDeque<Integer> d = new ArrayDeque<>(Arrays.asList(1, 2, 3)); Iterator<Integer> i = d.iterator(); i.next(); StringBuilder sb = new StringBuilder(); i.forEachRemaining(x -> { sb.append(x); d.addLast(7); }); return sb + " " + d; });
        t("iterator removeIf", () -> { ArrayDeque<Integer> d = new ArrayDeque<>(Arrays.asList(1, 2, 3, 4)); d.removeIf(x -> x % 2 == 0); return d; });
        t("LinkedList null ok", () -> { LinkedList<String> l = new LinkedList<>(); l.addFirst(null); l.offer(null); l.push(null); return l + " " + l.peek() + " " + l.poll() + " " + l.size(); });
        t("Queue view", () -> { Queue<Integer> q = new ArrayDeque<>(); q.add(1); q.offer(2); return q.peek() + " " + q.poll() + " " + q.remove() + " " + q.poll() + " " + q.peek(); });
        t("Queue remove empty", () -> new ArrayDeque<Integer>().remove());
        t("Queue element empty", () -> new LinkedList<Integer>().element());
        t("Stack use", () -> { Deque<String> s = new ArrayDeque<>(); s.push("a"); s.push("b"); s.push("c"); return s.pop() + s.pop() + s.peek() + s; });
        t("lifo queue", () -> { Queue<Integer> q = Collections.asLifoQueue(new ArrayDeque<Integer>()); q.add(1); q.add(2); q.offer(3); return q + " " + q.poll() + " " + q.peek() + " " + q.size(); });
        t("equals", () -> { ArrayDeque<Integer> d = new ArrayDeque<>(Arrays.asList(1, 2)); return d.equals(new ArrayDeque<Integer>(Arrays.asList(1, 2))) + " " + d.equals(d) + " " + new LinkedList<Integer>(d).equals(d); });
        StringBuilder caps = new StringBuilder("slots (size at which addFirst disturbs an iterator):");
        for (int c : new int[]{0, 1, 7, 8, 9, 15, 16, 17, 100}) { ArrayDeque<Integer> d = new ArrayDeque<>(c); d.add(1); caps.append(" (").append(c).append(")").append(slots(d)); }
        for (int c : new int[]{1, 7, 8, 16}) caps.append(" coll").append(c).append('=').append(slots(new ArrayDeque<>(Collections.nCopies(c, 0))));
        ArrayDeque<Integer> grown = new ArrayDeque<>();
        for (int i = 0; i < 40; i++) grown.add(i);
        for (int i = 0; i < 39; i++) grown.poll();
        System.out.println(caps + " default " + slots(new ArrayDeque<>(Collections.singleton(1))) + "/" + slots(addOne(new ArrayDeque<Integer>())) + " grown-then-drained " + slots(grown));
        t("remove at the middle (tie)", () -> { ArrayDeque<String> d = new ArrayDeque<>(Arrays.asList("a", "b", "c", "d")); Iterator<String> it = d.iterator(); it.next(); d.remove("c"); StringBuilder sb = new StringBuilder(); try { while (it.hasNext()) sb.append(it.next()); } catch (ConcurrentModificationException e) { sb.append("CME"); } return sb + " " + d; });
        System.out.println("done");
    }

    static ArrayDeque<Integer> addOne(ArrayDeque<Integer> d) { d.add(1); return d; }

    /** The deque's array length: the size at which adding at the front first breaks an open iterator. */
    static int slots(ArrayDeque<Integer> d) {
        for (int k = 0; k < 5000; k++) {
            ArrayDeque<Integer> c = d.clone();
            Iterator<Integer> it = c.iterator();
            for (int i = 0; i < k; i++) c.addFirst(-1);
            try { it.next(); } catch (ConcurrentModificationException e) { return c.size(); }
        }
        return -1;
    }
}
