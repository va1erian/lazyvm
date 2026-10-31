// VMARGS: -Xmx16m
package lazy.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * An add that fails for want of a bigger array changes nothing. A hand-written golden: the oracle's
 * deque is not usable after this (concurrent.md, "What is not pinned").
 */
public class DequeMemory {
    static List<Object> fill() {
        List<Object> hog = new ArrayList<>(100_000);
        for (int size = 1 << 20; size >= 16; size >>= 1) {
            try {
                while (true) hog.add(new byte[size]);
            } catch (OutOfMemoryError e) {
                // On to smaller pieces.
            }
        }
        return hog;
    }

    static void check(boolean atFront) {
        ArrayDeque<Object> d = new ArrayDeque<>(4000);
        Object filler = new Object();
        Object first = new Object();
        Object last = new Object();
        d.addLast(first);
        d.addLast(last);
        List<Object> hog = fill();
        int size = 2;
        boolean ran = false;
        try {
            for (int i = 0; i < 200_000; i++) {
                if (atFront) d.addFirst(filler); else d.addLast(filler);
                size++;
            }
        } catch (OutOfMemoryError e) {
            ran = true;
        }
        hog.clear();
        hog = null;
        String where = atFront ? "addFirst" : "addLast";
        System.out.println(where + " ran out " + ran + ", size kept " + (d.size() == size));
        System.out.println(where + " ends kept " + (atFront ? d.peekLast() == last : d.peekFirst() == first));
        d.addLast("x");
        d.addFirst("y");
        System.out.println(where + " still works " + d.peekLast() + " " + d.peekFirst() + " " + (d.size() == size + 2));
    }

    public static void main(String[] args) {
        check(false);
        check(true);
    }
}
