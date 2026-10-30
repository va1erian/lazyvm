package thread;

import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicStampedReference;

/** AtomicMarkableReference and AtomicStampedReference on one thread. */
public class AtomicPairs {
    static void p(Object o) {
        System.out.println(o);
    }

    static void marks() {
        String a = new String("a");
        String sameText = new String("a");
        AtomicMarkableReference<String> m = new AtomicMarkableReference<>(a, false);
        p(m.getReference() + " " + m.isMarked());
        boolean[] holder = {true, true};
        p(m.get(holder) + " " + holder[0] + " " + holder[1]);
        p(m.compareAndSet(sameText, "b", false, true) + " " + m.compareAndSet(a, "b", true, true) + " " + m.isMarked());
        p(m.compareAndSet(a, a, false, false) + " " + m.getReference());
        p(m.compareAndSet(a, "b", false, true) + " " + m.getReference() + " " + m.isMarked());
        p(m.weakCompareAndSet("zz", "c", true, false) + " " + m.weakCompareAndSet(m.getReference(), "c", true, false));
        p(m.getReference() + " " + m.isMarked());
        String c = m.getReference();
        p(m.attemptMark(new String(c), !m.isMarked()) + " " + m.isMarked());
        p(m.attemptMark("other", true) + " " + m.isMarked());
        p(m.attemptMark(c, true) + " " + m.isMarked() + " " + m.attemptMark(c, true) + " " + m.isMarked());
        m.set(null, false);
        p(m.getReference() + " " + m.isMarked() + " " + m.attemptMark(null, true) + " " + m.isMarked());
        p(m.compareAndSet(null, "n", true, false) + " " + m.getReference());
        try {
            m.get(null);
        } catch (NullPointerException e) {
            p("npe " + e.getMessage());
        }
        try {
            m.get(new boolean[0]);
        } catch (ArrayIndexOutOfBoundsException e) {
            p("aioobe " + e.getMessage());
        }
    }

    static void stamps() {
        String a = new String("a");
        String sameText = new String("a");
        AtomicStampedReference<String> s = new AtomicStampedReference<>(a, 7);
        p(s.getReference() + " " + s.getStamp());
        int[] holder = {-1, -1};
        p(s.get(holder) + " " + holder[0] + " " + holder[1]);
        p(s.compareAndSet(sameText, "b", 7, 8) + " " + s.compareAndSet(a, "b", 6, 8) + " " + s.getStamp());
        p(s.compareAndSet(a, a, 7, 7) + " " + s.getReference());
        p(s.compareAndSet(a, "b", 7, 8) + " " + s.getReference() + " " + s.getStamp());
        p(s.weakCompareAndSet("zz", "c", 8, 9) + " " + s.weakCompareAndSet(s.getReference(), "c", 8, 9));
        p(s.getReference() + " " + s.getStamp());
        String c = s.getReference();
        p(s.attemptStamp(new String(c), 77) + " " + s.getStamp());
        p(s.attemptStamp("other", 5) + " " + s.getStamp());
        p(s.attemptStamp(c, 5) + " " + s.getStamp() + " " + s.attemptStamp(c, 5) + " " + s.getStamp());
        s.set(null, Integer.MIN_VALUE);
        p(s.getReference() + " " + s.getStamp() + " " + s.attemptStamp(null, 0) + " " + s.getStamp());
        p(s.compareAndSet(null, "n", 0, 1) + " " + s.getReference());
        try {
            s.get(null);
        } catch (NullPointerException e) {
            p("npe " + e.getMessage());
        }
        try {
            s.get(new int[0]);
        } catch (ArrayIndexOutOfBoundsException e) {
            p("aioobe " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        marks();
        stamps();
    }
}
