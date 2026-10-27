package util;

import java.util.*;
import java.util.function.*;

/** An iterator used from inside its own forEachRemaining action, and walks over a changed list (spec 9.F2, 4.D2, 4.D3). */
public class ReentrantWalks {
    static String ex(Throwable t) { return t.getClass().getSimpleName(); }

    static String tr(Supplier<Object> s) {
        try { return String.valueOf(s.get()); } catch (RuntimeException e) { return ex(e); }
    }

    static List<Supplier<List<Integer>>> makers() {
        List<Supplier<List<Integer>>> m = new ArrayList<>();
        m.add(() -> new ArrayList<>(Arrays.asList(0, 1, 2, 3, 4, 5)));
        m.add(() -> new LinkedList<>(Arrays.asList(0, 1, 2, 3, 4, 5)));
        m.add(() -> new Vector<>(Arrays.asList(0, 1, 2, 3, 4, 5)));
        m.add(() -> new ArrayList<>(Arrays.asList(9, 0, 1, 2, 3, 4, 5, 9)).subList(1, 7));
        m.add(() -> Collections.checkedList(new LinkedList<>(Arrays.asList(0, 1, 2, 3, 4, 5)), Integer.class));
        m.add(() -> Collections.unmodifiableList(new LinkedList<>(Arrays.asList(0, 1, 2, 3, 4, 5))));
        return m;
    }

    public static void main(String[] args) {
        String[] kinds = {"ArrayList", "LinkedList", "Vector", "ArrayList window", "checked LinkedList", "unmodifiable LinkedList"};
        String[] modes = {"remove odd", "add 77 once", "set +10", "nextIndex", "throw at 2"};
        List<Supplier<List<Integer>>> makers = makers();
        for (int k = 0; k < kinds.length; k++) {
            for (int mode = 0; mode < modes.length; mode++) {
                List<Integer> l = makers.get(k).get();
                ListIterator<Integer> li = l.listIterator(1);
                StringBuilder w = new StringBuilder();
                int[] c = {0};
                final int md = mode;
                try {
                    li.forEachRemaining(x -> {
                        w.append(x).append(',');
                        c[0]++;
                        try {
                            if (md == 0 && c[0] % 2 == 1) li.remove();
                            if (md == 1 && c[0] == 1) li.add(77);
                            if (md == 2) li.set(x + 10);
                            if (md == 3) w.append('i').append(li.nextIndex()).append(';');
                        } catch (RuntimeException e) { w.append('[').append(ex(e)).append(']'); }
                        if (md == 4 && c[0] == 2) throw new IllegalArgumentException();
                    });
                    w.append(" done");
                } catch (RuntimeException e) { w.append(" !").append(ex(e)); }
                String after = "next " + tr(li::next) + " index " + tr(li::nextIndex);
                System.out.println(kinds[k] + " " + modes[mode] + ": " + w + " | " + after + " => " + tr(l::toString));
            }
        }
        // The same iterator's forEachRemaining run again from inside its own action, per list type.
        for (int k = 0; k < 4; k++) {
            List<Integer> l = makers.get(k).get();
            ListIterator<Integer> li = l.listIterator(2);
            StringBuilder outer = new StringBuilder(), inner = new StringBuilder();
            int[] n = {0};
            String r = tr(() -> { li.forEachRemaining(x -> { outer.append(x); if (++n[0] == 2) li.forEachRemaining(inner::append); }); return "done"; });
            System.out.println(kinds[k] + " nested forEachRemaining: " + r + " outer " + outer + " inner " + inner + " | index " + tr(li::nextIndex)
                + " hasNext " + tr(li::hasNext) + " remove " + tr(() -> { li.remove(); return "ok"; }) + " => " + tr(l::toString));
        }
        // A wrapper's list iterator hands forEachRemaining on; its plain iterator steps through next().
        for (int w = 0; w < 2; w++) {
            LinkedList<Integer> ll = new LinkedList<>(Arrays.asList(1, 2, 3, 4));
            List<Integer> v = w == 0 ? Collections.unmodifiableList(ll) : Collections.checkedList(ll, Integer.class);
            String viaList = tr(() -> { v.listIterator().forEachRemaining(x -> { if (x == 4) ll.removeFirst(); }); return "ok"; });
            LinkedList<Integer> ll2 = new LinkedList<>(Arrays.asList(1, 2, 3, 4));
            List<Integer> v2 = w == 0 ? Collections.unmodifiableList(ll2) : Collections.checkedList(ll2, Integer.class);
            String viaIt = tr(() -> { v2.iterator().forEachRemaining(x -> { if (x == 4) ll2.removeFirst(); }); return "ok"; });
            System.out.println((w == 0 ? "unmodifiable" : "checked") + " wrapper over LinkedList, removal at the last element: listIterator " + viaList + ", iterator " + viaIt);
        }
        // Spec 9.F2: an ArrayList window whose ArrayList storage shrank under it.
        ArrayList<Integer> root = new ArrayList<>(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7));
        List<Integer> win = root.subList(4, 8);
        Iterator<Integer> wi = win.iterator();
        for (int i = 0; i < 4; i++) root.remove(root.size() - 1);
        root.trimToSize();
        StringBuilder seen = new StringBuilder();
        System.out.println("window walk over shrunk storage: " + tr(() -> { wi.forEachRemaining(seen::append); return "ok"; }) + " saw [" + seen + "]");
        // Spec 4.D2: the remove check against the deque's ends, including the slot just at the end.
        StringBuilder ends = new StringBuilder("deque remove after the walked slot left:");
        for (int n = 1; n <= 3; n++) {
            ArrayDeque<Integer> d = new ArrayDeque<>(Arrays.asList(1, 2, 3));
            Iterator<Integer> it = d.iterator();
            for (int i = 0; i < n; i++) it.next();
            d.pollLast();
            ends.append(" after ").append(n).append(" next ").append(tr(() -> { it.remove(); return "ok"; })).append(d);
        }
        System.out.println(ends);
        // Spec 4.D3: clear() on an empty deque leaves its ends in place.
        ArrayDeque<Integer> d = new ArrayDeque<>(2);
        d.add(33); d.add(1);
        d.pollLast();
        Iterator<Integer> it = d.iterator();
        it.next();
        d.pollFirst();
        d.clear();
        d.addLast(24);
        System.out.println("clear of an empty deque: remove " + tr(() -> { it.remove(); return "ok"; }) + " " + d);
        // A window of a LinkedList tracks its iterator's place itself (spec 8.C1).
        List<Integer> base = new LinkedList<>(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7));
        ListIterator<Integer> wl = base.subList(1, 7).subList(1, 5).listIterator();
        StringBuilder steps = new StringBuilder();
        steps.append(wl.next()).append(' ').append(wl.next()).append(" remove after next: ");
        wl.remove();
        steps.append(wl.nextIndex()).append('/').append(wl.previousIndex()).append(' ').append(wl.next()).append(" previous ").append(wl.previous());
        wl.remove();
        steps.append(" remove after previous: ").append(wl.nextIndex()).append(' ').append(wl.hasPrevious()).append(' ').append(wl.next());
        wl.add(9);
        steps.append(" add: ").append(wl.nextIndex()).append(' ').append(wl.hasNext()).append(' ').append(tr(wl::next)).append(' ').append(tr(wl::next));
        System.out.println("window of a window iterator: " + steps + " => " + base);
        System.out.println("done");
    }
}
