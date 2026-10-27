package java.util;

public abstract class AbstractCollection<E> implements Collection<E> {
    protected AbstractCollection() {
    }

    public abstract Iterator<E> iterator();

    public abstract int size();

    public boolean isEmpty() { return size() == 0; }

    public boolean contains(Object o) {
        for (Iterator<E> it = iterator(); it.hasNext();) {
            if (Support.eq(o, it.next())) {
                return true;
            }
        }
        return false;
    }

    public Object[] toArray() {
        Object[] out = new Object[size()];
        int n = 0;
        for (Iterator<E> it = iterator(); it.hasNext();) {
            if (n == out.length) {
                out = Arrays.copyOf(out, n + (n >> 1) + 1);
            }
            out[n++] = it.next();
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    /** Stores element by element, so a wrong array type fails naming the element's class. */
    public <T> T[] toArray(T[] a) {
        Object[] items = toArray();
        return Support.storeEach(items, items.length, a);
    }

    public boolean add(E e) { throw new UnsupportedOperationException(); }

    public boolean remove(Object o) {
        for (Iterator<E> it = iterator(); it.hasNext();) {
            if (Support.eq(o, it.next())) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public boolean containsAll(Collection<?> c) {
        for (Object e : c) {
            if (!contains(e)) {
                return false;
            }
        }
        return true;
    }

    public boolean addAll(Collection<? extends E> c) {
        boolean changed = false;
        for (E e : c) {
            changed |= add(e);
        }
        return changed;
    }

    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return removeWhere(c, true);
    }

    public boolean retainAll(Collection<?> c) {
        Objects.requireNonNull(c);
        return removeWhere(c, false);
    }

    /** Removes, through the iterator, each element whose membership in c equals `inC`. */
    boolean removeWhere(Collection<?> c, boolean inC) {
        boolean changed = false;
        for (Iterator<E> it = iterator(); it.hasNext();) {
            if (c.contains(it.next()) == inC) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    public void clear() {
        for (Iterator<E> it = iterator(); it.hasNext();) {
            it.next();
            it.remove();
        }
    }

    public String toString() {
        Iterator<E> it = iterator();
        if (!it.hasNext()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        while (true) {
            sb.append(Support.show(it.next(), this, "(this Collection)"));
            if (!it.hasNext()) {
                return sb.append(']').toString();
            }
            sb.append(", ");
        }
    }
}
