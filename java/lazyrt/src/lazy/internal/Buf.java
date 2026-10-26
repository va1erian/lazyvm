package lazy.internal;

/** A growable list of objects; lazyrt has no collections yet. */
public final class Buf {
    private Object[] items = new Object[8];
    private int size;

    public void add(Object o) {
        if (size == items.length) {
            Object[] bigger = new Object[size * 2];
            System.arraycopy(items, 0, bigger, 0, size);
            items = bigger;
        }
        items[size++] = o;
    }

    public int size() {
        return size;
    }

    public Object get(int i) {
        return items[i];
    }

    public void set(int i, Object o) {
        items[i] = o;
    }

    /** The elements as an array whose runtime component type is `component`. */
    public Object[] toArray(Class<?> component) {
        Object[] out = (Object[]) java.lang.reflect.Array.newInstance(component, size);
        System.arraycopy(items, 0, out, 0, size);
        return out;
    }
}
