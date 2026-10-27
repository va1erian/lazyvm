package util;

import java.util.*;
import java.util.function.*;

/** LinkedList as a List: indexing, bulk operations, list iterators and exception messages. */
public class LinkedLists {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static LinkedList<Integer> ll(int... xs) {
        LinkedList<Integer> l = new LinkedList<>();
        for (int x : xs) l.add(x);
        return l;
    }

    public static void main(String[] args) {
        LinkedList<Integer> a = ll(4, 8, 15, 16, 23, 42);
        System.out.println(a + " " + a.size() + " " + a.getFirst() + " " + a.getLast() + " " + a.get(3) + " " + a.indexOf(23) + " " + a.lastIndexOf(5));
        a.add(2, 99); a.add(0, 1); a.add(a.size(), 100);
        System.out.println(a);
        t("get -1", () -> a.get(-1));
        t("get size", () -> a.get(a.size()));
        t("set bad", () -> a.set(50, 1));
        t("add bad", () -> { a.add(50, 1); return a; });
        t("remove bad", () -> a.remove(50));
        t("remove obj", () -> a.remove((Object) 99) + " " + a);
        t("remove obj missing", () -> a.remove((Object) 77));
        t("set", () -> a.set(1, 5) + " " + a);
        t("addAll", () -> a.addAll(ll(7, 7)) + " " + a);
        t("addAll@mid", () -> a.addAll(3, ll(6, 6, 6)) + " " + a);
        t("addAll@0", () -> a.addAll(0, ll(0)) + " " + a);
        t("addAll@end", () -> a.addAll(a.size(), ll(-1)) + " " + a);
        t("addAll@bad", () -> a.addAll(99, ll(1)));
        t("addAll empty", () -> a.addAll(new ArrayList<Integer>()));
        t("removeAll", () -> a.removeAll(ll(6, 7)) + " " + a);
        t("retainAll", () -> a.retainAll(ll(0, 4, 5, 15, 100)) + " " + a);
        t("contains", () -> a.contains(15) + " " + a.contains(1));
        t("first/last", () -> a.removeFirst() + " " + a.removeLast() + " " + a);
        t("removeFirstOccurrence", () -> a.removeFirstOccurrence(5) + " " + a.removeLastOccurrence(15) + " " + a);
        LinkedList<Integer> e = new LinkedList<>();
        t("empty getFirst", () -> e.getFirst());
        t("empty getLast", () -> e.getLast());
        t("empty removeFirst", () -> e.removeFirst());
        t("empty removeLast", () -> e.removeLast());
        t("empty element", () -> e.element());
        t("empty remove()", () -> e.remove());
        t("empty pop", () -> e.pop());
        t("empty peek/poll", () -> e.peek() + " " + e.poll() + " " + e.peekFirst() + " " + e.peekLast() + " " + e.pollFirst() + " " + e.pollLast());
        LinkedList<Integer> b = ll(1, 2, 3, 4, 5, 6, 7, 8, 9);
        ListIterator<Integer> it = b.listIterator();
        StringBuilder sb = new StringBuilder();
        while (it.hasNext()) {
            int idx = it.nextIndex();
            int v = it.next();
            if (v % 3 == 0) it.remove();
            else if (v % 3 == 1) it.add(v * 10);
            else it.set(v * 100);
            sb.append(idx).append(':').append(v).append(' ');
        }
        System.out.println(sb + "-> " + b);
        sb.setLength(0);
        while (it.hasPrevious()) sb.append(it.previousIndex()).append('=').append(it.previous()).append(' ');
        System.out.println(sb);
        t("iter remove twice", () -> { ListIterator<Integer> i = ll(1, 2).listIterator(); i.next(); i.remove(); i.remove(); return 0; });
        t("iter set before next", () -> { ll(1).listIterator().set(5); return 0; });
        t("iter set after add", () -> { ListIterator<Integer> i = ll(1).listIterator(); i.next(); i.add(3); i.set(4); return 0; });
        t("iter next end", () -> { ll(1).listIterator(1).next(); return 0; });
        t("iter previous start", () -> { ll(1).listIterator().previous(); return 0; });
        t("listIterator bad", () -> ll(1, 2).listIterator(3));
        t("listIterator neg", () -> ll(1, 2).listIterator(-1));
        LinkedList<Integer> c = ll(1, 2, 3, 4);
        Iterator<Integer> di = c.descendingIterator();
        sb.setLength(0);
        while (di.hasNext()) { int v = di.next(); sb.append(v); if (v == 3) di.remove(); }
        System.out.println(sb + " " + c);
        t("CME iterator", () -> { for (Integer x : c) if (x == 2) c.add(9); return c; });
        t("CME listIterator", () -> { ListIterator<Integer> i = c.listIterator(); c.add(5); return i.next(); });
        t("CME hasNext ok", () -> { Iterator<Integer> i = c.iterator(); i.next(); c.removeFirst(); return i.hasNext(); });
        t("forEach CME", () -> { c.forEach(x -> c.add(1)); return 0; });
        t("subList", () -> { LinkedList<Integer> d = ll(1, 2, 3, 4, 5, 6); List<Integer> s = d.subList(1, 5); s.remove(0); s.add(50); s.set(0, 30); return s + " " + d + " " + s.size(); });
        t("subList CME", () -> { LinkedList<Integer> d = ll(1, 2, 3, 4); List<Integer> s = d.subList(1, 3); d.add(5); return s.get(0); });
        t("subList bad", () -> ll(1, 2).subList(1, 5));
        t("sort", () -> { LinkedList<Integer> d = ll(5, 2, 8, 1); d.sort(null); return d; });
        t("replaceAll/removeIf", () -> { LinkedList<Integer> d = ll(5, 2, 8, 1); d.replaceAll(x -> x + 1); d.removeIf(x -> x % 2 == 0); return d; });
        t("clone", () -> { LinkedList<Integer> d = ll(1, 2); @SuppressWarnings("unchecked") LinkedList<Integer> k = (LinkedList<Integer>) d.clone(); k.add(3); return d + " " + k; });
        t("equals", () -> ll(1, 2, 3).equals(Arrays.asList(1, 2, 3)) + " " + ll(1, 2, 3).equals(ll(1, 2)) + " " + ll(1, 2, 3).hashCode());
        t("toArray", () -> Arrays.toString(ll(1, 2, 3).toArray()) + Arrays.toString(ll(1, 2, 3).toArray(new Integer[5])) + ll(1).toArray(new Integer[0]).length);
        t("toArray bad", () -> ll(1).toArray(new String[0]));
        t("nulls", () -> { LinkedList<String> n = new LinkedList<>(); n.add(null); n.addFirst(null); n.push("p"); return n + " " + n.indexOf(null) + " " + n.lastIndexOf(null) + " " + n.contains(null) + " " + n.remove(null) + " " + n; });
        t("ctor", () -> new LinkedList<Integer>(Arrays.asList(3, 2, 1)) + " " + new LinkedList<Integer>(null));
        t("large", () -> { LinkedList<Integer> l = new LinkedList<>(); for (int i = 0; i < 2000; i++) l.add(i); long s = 0; for (int i = 0; i < 2000; i += 37) s += l.get(i); return s + " " + l.indexOf(1999) + " " + l.get(1000); });
        System.out.println("done");
    }
}
