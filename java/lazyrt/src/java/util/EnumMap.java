package java.util;

/**
 * One value slot per constant of the key enum, in ordinal order. A null value is stored as a
 * marker so that "absent" and "mapped to null" differ. Spec 9.F4: iterators read the slots live.
 */
public class EnumMap<K extends Enum<K>, V> extends AbstractMap<K, V> implements java.io.Serializable, Cloneable {
    private static final Object HELD_NULL = new Object();

    private final Class<K> keyClass;
    private transient K[] constants;
    private transient Object[] slots;
    private transient int size;
    private transient Set<Map.Entry<K, V>> entryView;

    public EnumMap(Class<K> keyClass) {
        this.keyClass = keyClass;
        this.constants = keyClass.getEnumConstants();
        this.slots = new Object[constants.length];
    }

    public EnumMap(EnumMap<K, ? extends V> m) {
        this.keyClass = m.keyClass;
        this.constants = m.constants;
        this.slots = m.slots.clone();
        this.size = m.size;
    }

    @SuppressWarnings("unchecked")
    public EnumMap(Map<K, ? extends V> m) {
        if (m instanceof EnumMap) {
            EnumMap<K, ? extends V> em = (EnumMap<K, ? extends V>) m;
            this.keyClass = em.keyClass;
            this.constants = em.constants;
            this.slots = em.slots.clone();
            this.size = em.size;
            return;
        }
        if (m.isEmpty()) {
            throw new IllegalArgumentException("Specified map is empty");
        }
        this.keyClass = m.keySet().iterator().next().getDeclaringClass();
        this.constants = keyClass.getEnumConstants();
        this.slots = new Object[constants.length];
        putAll(m);
    }

    /** Whether o is a constant of the key enum (a constant with a body is a subclass of it). */
    private boolean isKey(Object o) {
        return o != null && (o.getClass() == keyClass || o.getClass().getSuperclass() == keyClass);
    }

    // Spec 11: the wrong enum fails as "class A != class B".
    private void checkKey(K key) {
        Class<?> c = key.getClass();
        if (c != keyClass && c.getSuperclass() != keyClass) {
            throw new ClassCastException(c + " != " + keyClass);
        }
    }

    @SuppressWarnings("unchecked")
    private V reveal(Object slot) { return slot == HELD_NULL ? null : (V) slot; }
    public int size() { return size; }

    public boolean containsValue(Object value) {
        Object want = value == null ? HELD_NULL : value;
        for (Object slot : slots) {
            if (slot != null && want.equals(slot)) {
                return true;
            }
        }
        return false;
    }

    public boolean containsKey(Object key) { return isKey(key) && slots[((Enum<?>) key).ordinal()] != null; }
    public V get(Object key) { return isKey(key) ? reveal(slots[((Enum<?>) key).ordinal()]) : null; }

    public V put(K key, V value) {
        checkKey(key);
        int i = key.ordinal();
        Object old = slots[i];
        slots[i] = value == null ? HELD_NULL : value;
        if (old == null) {
            size++;
        }
        return reveal(old);
    }

    public V remove(Object key) {
        if (!isKey(key)) {
            return null;
        }
        return clearSlot(((Enum<?>) key).ordinal());
    }

    final V clearSlot(int i) {
        Object old = slots[i];
        if (old != null) {
            slots[i] = null;
            size--;
        }
        return reveal(old);
    }

    @SuppressWarnings("unchecked")
    public void putAll(Map<? extends K, ? extends V> m) {
        if (m instanceof EnumMap) {
            EnumMap<?, ?> em = (EnumMap<?, ?>) m;
            if (em.keyClass != keyClass) {
                if (em.isEmpty()) {
                    return;
                }
                throw new ClassCastException(em.keyClass + " != " + keyClass);
            }
        }
        super.putAll(m);
    }

    public void clear() {
        Arrays.fill(slots, null);
        size = 0;
    }

    public Set<K> keySet() {
        if (keyView == null) {
            keyView = new Keys();
        }
        return keyView;
    }

    public Collection<V> values() { return super.values(); }

    public Set<Map.Entry<K, V>> entrySet() {
        if (entryView == null) {
            entryView = new AbstractSet<Map.Entry<K, V>>() {
                public Iterator<Map.Entry<K, V>> iterator() { return new Walk<Map.Entry<K, V>>(true); }
                public int size() { return size; }
                public void clear() { EnumMap.this.clear(); }
            };
        }
        return entryView;
    }

    public boolean equals(Object o) {
        if (o instanceof EnumMap) {
            EnumMap<?, ?> em = (EnumMap<?, ?>) o;
            if (em.keyClass != keyClass) {
                return size == 0 && em.size == 0;
            }
            return Arrays.equals(slots, em.slots);
        }
        return super.equals(o);
    }

    public int hashCode() {
        int h = 0;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) {
                h += constants[i].hashCode() ^ Support.hash(reveal(slots[i]));
            }
        }
        return h;
    }

    @SuppressWarnings("unchecked")
    public EnumMap<K, V> clone() {
        try {
            EnumMap<K, V> copy = (EnumMap<K, V>) super.clone();
            copy.slots = slots.clone();
            copy.entryView = null;
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    private final class Keys extends AbstractSet<K> {
        public Iterator<K> iterator() { return new Walk<K>(false); }
        public int size() { return size; }
        public boolean contains(Object o) { return containsKey(o); }

        public boolean remove(Object o) {
            int before = size;
            EnumMap.this.remove(o);
            return size != before;
        }

        public void clear() { EnumMap.this.clear(); }
    }

    /** Spec 9.F4: steps over the slots as they are now; never throws for changes. */
    private final class Walk<T> implements Iterator<T> {
        private final boolean entries;
        private int index;
        private int last = -1;
        private Slot lastSlot;

        Walk(boolean entries) { this.entries = entries; }

        public boolean hasNext() {
            while (index < slots.length && slots[index] == null) {
                index++;
            }
            return index != slots.length;
        }

        @SuppressWarnings("unchecked")
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            last = index++;
            if (!entries) {
                return (T) constants[last];
            }
            lastSlot = new Slot(last);
            return (T) lastSlot;
        }

        public void remove() {
            if (last < 0) {
                throw new IllegalStateException();
            }
            clearSlot(last);
            last = -1;
            if (lastSlot != null) {
                lastSlot.index = -1;
                lastSlot = null;
            }
        }
    }

    /** An entry that reads its slot live, until removed through the iterator. */
    private final class Slot implements Map.Entry<K, V> {
        int index;

        Slot(int index) { this.index = index; }

        private int at() {
            if (index < 0) {
                throw new IllegalStateException("Entry was removed");
            }
            return index;
        }

        public K getKey() { return constants[at()]; }
        public V getValue() { return reveal(slots[at()]); }

        public V setValue(V value) {
            int i = at();
            V old = reveal(slots[i]);
            slots[i] = value == null ? HELD_NULL : value;
            return old;
        }

        public boolean equals(Object o) { return index >= 0 ? AbstractMap.entryEquals(this, o) : o == this; }
        public int hashCode() { return index >= 0 ? AbstractMap.entryHash(this) : System.identityHashCode(this); }
        public String toString() { return index >= 0 ? getKey() + "=" + getValue() : super.toString(); }
    }
}
