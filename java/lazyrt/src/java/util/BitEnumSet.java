package java.util;

/**
 * The storage of both EnumSet classes: bit (ordinal % 64) of word (ordinal / 64) says whether
 * that constant is in the set.
 */
abstract class BitEnumSet<E extends Enum<E>> extends EnumSet<E> {
    private long[] words;
    private int size;

    BitEnumSet(Class<E> elementType, Enum<?>[] universe) {
        super(elementType, universe);
        words = new long[(universe.length + 63) >>> 6];
    }

    private boolean isMember(Object o) {
        return o != null && (o.getClass() == elementType || o.getClass().getSuperclass() == elementType);
    }

    // Spec 11: the wrong enum fails as "class A != class B".
    private void check(E e) {
        Class<?> c = e.getClass();
        if (c != elementType && c.getSuperclass() != elementType) {
            throw new ClassCastException(c + " != " + elementType);
        }
    }

    private void recount() {
        size = 0;
        for (long w : words) {
            size += Long.bitCount(w);
        }
    }

    /** Another BitEnumSet of the same enum, or null. */
    private BitEnumSet<?> sameKind(Collection<?> c) {
        return c instanceof BitEnumSet && ((BitEnumSet<?>) c).elementType == elementType ? (BitEnumSet<?>) c : null;
    }

    private static boolean otherEnum(Collection<?> c, Class<?> type) {
        return c instanceof BitEnumSet && ((BitEnumSet<?>) c).elementType != type;
    }

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    public boolean contains(Object o) {
        if (!isMember(o)) {
            return false;
        }
        int i = ((Enum<?>) o).ordinal();
        return (words[i >>> 6] & (1L << i)) != 0;
    }

    public boolean add(E e) {
        check(e);
        int i = e.ordinal();
        long old = words[i >>> 6];
        words[i >>> 6] |= 1L << i;
        if (old == words[i >>> 6]) {
            return false;
        }
        size++;
        return true;
    }

    public boolean remove(Object o) {
        if (!contains(o)) {
            return false;
        }
        int i = ((Enum<?>) o).ordinal();
        words[i >>> 6] &= ~(1L << i);
        size--;
        return true;
    }

    public void clear() {
        Arrays.fill(words, 0);
        size = 0;
    }

    public boolean containsAll(Collection<?> c) {
        if (otherEnum(c, elementType)) {
            return c.isEmpty();
        }
        BitEnumSet<?> s = sameKind(c);
        if (s == null) {
            return super.containsAll(c);
        }
        for (int i = 0; i < words.length; i++) {
            if ((s.words[i] & ~words[i]) != 0) {
                return false;
            }
        }
        return true;
    }

    public boolean addAll(Collection<? extends E> c) {
        if (otherEnum(c, elementType)) {
            if (c.isEmpty()) {
                return false;
            }
            throw new ClassCastException(((BitEnumSet<?>) c).elementType + " != " + elementType);
        }
        BitEnumSet<?> s = sameKind(c);
        if (s == null) {
            return super.addAll(c);
        }
        return combine(s, 0);
    }

    public boolean removeAll(Collection<?> c) {
        if (otherEnum(c, elementType)) {
            return false;
        }
        BitEnumSet<?> s = sameKind(c);
        return s == null ? super.removeAll(c) : combine(s, 1);
    }

    public boolean retainAll(Collection<?> c) {
        if (otherEnum(c, elementType)) {
            boolean changed = size != 0;
            clear();
            return changed;
        }
        BitEnumSet<?> s = sameKind(c);
        return s == null ? super.retainAll(c) : combine(s, 2);
    }

    /** Word by word: 0 adds s, 1 removes s, 2 keeps only s. Returns whether anything changed. */
    private boolean combine(BitEnumSet<?> s, int how) {
        boolean changed = false;
        for (int i = 0; i < words.length; i++) {
            long old = words[i];
            words[i] = how == 0 ? old | s.words[i] : how == 1 ? old & ~s.words[i] : old & s.words[i];
            changed |= old != words[i];
        }
        recount();
        return changed;
    }

    public boolean equals(Object o) {
        if (!(o instanceof BitEnumSet)) {
            return super.equals(o);
        }
        BitEnumSet<?> s = (BitEnumSet<?>) o;
        if (s.elementType != elementType) {
            return size == 0 && s.size == 0;
        }
        return Arrays.equals(words, s.words);
    }

    void addRange(int from, int to) {
        for (int i = from; i < to; i++) {
            words[i >>> 6] |= 1L << i;
        }
        recount();
    }

    void complement() {
        for (int i = 0; i < universe.length; i++) {
            words[i >>> 6] ^= 1L << i;
        }
        recount();
    }

    void ownBits() { words = words.clone(); }

    /** Spec 9.F4: each word of bits is captured when the walk reaches it. */
    public Iterator<E> iterator() {
        return new Iterator<E>() {
            private int word;
            private long unseen = words.length > 0 ? words[0] : 0;
            private long lastBit;
            private int lastWord;

            public boolean hasNext() {
                while (unseen == 0 && word < words.length - 1) {
                    unseen = words[++word];
                }
                return unseen != 0;
            }

            @SuppressWarnings("unchecked")
            public E next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                lastBit = unseen & -unseen;
                lastWord = word;
                unseen -= lastBit;
                return (E) universe[(lastWord << 6) + Long.numberOfTrailingZeros(lastBit)];
            }

            public void remove() {
                if (lastBit == 0) {
                    throw new IllegalStateException();
                }
                long old = words[lastWord];
                words[lastWord] &= ~lastBit;
                if (old != words[lastWord]) {
                    size--;
                }
                lastBit = 0;
            }
        };
    }
}
