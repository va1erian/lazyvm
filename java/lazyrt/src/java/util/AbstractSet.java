package java.util;

public abstract class AbstractSet<E> extends AbstractCollection<E> implements Set<E> {
    protected AbstractSet() {
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Set)) {
            return false;
        }
        Collection<?> c = (Collection<?>) o;
        if (c.size() != size()) {
            return false;
        }
        try {
            return containsAll(c);
        } catch (ClassCastException | NullPointerException unlike) {
            return false;
        }
    }

    public int hashCode() {
        int h = 0;
        for (E e : this) {
            h += Support.hash(e);
        }
        return h;
    }

    // Spec 1.H11: a set larger than c removes each element of c; otherwise it iterates itself.
    public boolean removeAll(Collection<?> c) {
        Objects.requireNonNull(c);
        if (size() <= c.size()) {
            return removeWhere(c, true);
        }
        boolean changed = false;
        for (Object e : c) {
            changed |= remove(e);
        }
        return changed;
    }
}
