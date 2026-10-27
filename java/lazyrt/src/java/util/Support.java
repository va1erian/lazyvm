package java.util;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Checks and small routines shared by the collection classes, so each lives in one place. */
final class Support {
    private Support() {
    }

    /**
     * Natural order. Spec 11: only the left operand is cast to Comparable, so a right operand of
     * the wrong type fails inside its compareTo.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static final Comparator<Object> NATURAL = (x, y) -> ((Comparable) x).compareTo(y);

    /** Spec 8.C3: equality runs on the argument (or target) object, never on the stored one. */
    static boolean eq(Object probe, Object stored) { return probe == null ? stored == null : probe.equals(stored); }
    static int hash(Object o) { return o == null ? 0 : o.hashCode(); }

    /** Spec 11: the "Index: i, Size: n" message of the list classes. */
    static IndexOutOfBoundsException badIndex(int index, int size) {
        return new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
    }

    /** An element index: 0 <= index < size. */
    static void checkIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw badIndex(index, size);
        }
    }

    /** A position between elements: 0 <= index <= size. */
    static void checkPosition(int index, int size) {
        if (index < 0 || index > size) {
            throw badIndex(index, size);
        }
    }

    /** Spec 11: the subList bound messages. */
    static void checkSubList(int from, int to, int size) {
        if (from < 0) {
            throw new IndexOutOfBoundsException("fromIndex = " + from);
        }
        if (to > size) {
            throw new IndexOutOfBoundsException("toIndex = " + to);
        }
        if (from > to) {
            throw new IllegalArgumentException("fromIndex(" + from + ") > toIndex(" + to + ")");
        }
    }

    /** Spec 11: a negative capacity in the list and table constructors. */
    static void checkCapacity(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Illegal Capacity: " + capacity);
        }
    }

    /** Spec 10.X5: an element that is the collection itself prints as a marker, not recursively. */
    static String show(Object element, Object self, String marker) {
        return element == self ? marker : String.valueOf(element);
    }

    /**
     * toArray(T[]) over a snapshot of n elements: reuse `a` when it is large enough (marking the
     * end with null), else a new array of a's runtime type. Copies with arraycopy.
     */
    @SuppressWarnings("unchecked")
    static <T> T[] toArray(Object[] items, int n, T[] a) {
        if (a.length < n) {
            return (T[]) Arrays.copyOf(items, n, a.getClass());
        }
        System.arraycopy(items, 0, a, 0, n);
        if (a.length > n) {
            a[n] = null;
        }
        return a;
    }

    /** toArray(T[]) storing one element at a time, so a wrong type fails naming the element's class. */
    @SuppressWarnings("unchecked")
    static <T> T[] storeEach(Object[] items, int n, T[] a) {
        T[] out = a.length >= n ? a : (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), n);
        for (int i = 0; i < n; i++) {
            out[i] = (T) items[i];
        }
        if (out.length > n) {
            out[n] = null;
        }
        return out;
    }

    static NoSuchElementException noElement() { return new NoSuchElementException(); }

    /** `a` as an array whose runtime type is exactly Object[]. */
    static Object[] plainArray(Object[] a) {
        return a.getClass() == Object[].class ? a : Arrays.copyOf(a, a.length, Object[].class);
    }

    static int indexOf(Object[] items, int from, int to, Object o) {
        for (int i = from; i < to; i++) {
            if (eq(o, items[i])) {
                return i;
            }
        }
        return -1;
    }

    /** The last index at or below `from` holding an element equal to o, or -1. */
    static int lastIndexOf(Object[] items, int from, Object o) {
        for (int i = from; i >= 0; i--) {
            if (eq(o, items[i])) {
                return i;
            }
        }
        return -1;
    }

    // Spec 9.F3: the array lists stop right after an action that changed the list, then throw.
    static void forEach(AbstractList<?> owner, Object[] items, int size, Consumer<Object> action) {
        Objects.requireNonNull(action);
        int expected = owner.modCount;
        for (int i = 0; i < size && owner.modCount == expected; i++) {
            action.accept(items[i]);
        }
        checkUnchanged(owner, expected);
    }

    static void replaceAll(AbstractList<?> owner, Object[] items, int size, UnaryOperator<Object> op) {
        Objects.requireNonNull(op);
        int expected = owner.modCount;
        for (int i = 0; i < size && owner.modCount == expected; i++) {
            items[i] = op.apply(items[i]);
        }
        checkUnchanged(owner, expected);
    }

    /**
     * Tests every element first (stopping if a test changed the list), then packs the survivors
     * to the front and clears the rest. Returns how many survived.
     */
    static int sweep(AbstractList<?> owner, Object[] items, int size, Predicate<Object> doomed) {
        int expected = owner.modCount;
        boolean[] gone = new boolean[size];
        int kept = size;
        for (int i = 0; i < size && owner.modCount == expected; i++) {
            if (doomed.test(items[i])) {
                gone[i] = true;
                kept--;
            }
        }
        checkUnchanged(owner, expected);
        int w = 0;
        for (int i = 0; i < size; i++) {
            if (!gone[i]) {
                items[w++] = items[i];
            }
        }
        Arrays.fill(items, kept, size, null);
        return kept;
    }

    static void checkUnchanged(AbstractList<?> owner, int expected) {
        if (owner.modCount != expected) {
            throw new ConcurrentModificationException();
        }
    }
}
