package java.util;

/**
 * The algorithms follow spec 8.C2 call for call; the wrapper and factory classes live in
 * Unmodifiable, Synchronized, Checked and FixedCollections.
 */
public class Collections {
    // Spec 8.C2: the sizes below which a list that is not RandomAccess still uses get and set.
    private static final int REVERSE_INDEXED = 18;
    private static final int SHUFFLE_INDEXED = 5;
    private static final int FILL_INDEXED = 25;
    private static final int COPY_INDEXED = 10;
    private static final int ROTATE_INDEXED = 100;
    private static final int REPLACE_INDEXED = 11;
    private static final int SEARCH_INDEXED = 5000;

    @SuppressWarnings("rawtypes")
    public static final Set EMPTY_SET = new FixedCollections.EmptySet<Object>();
    @SuppressWarnings("rawtypes")
    public static final List EMPTY_LIST = new FixedCollections.EmptyList<Object>();
    @SuppressWarnings("rawtypes")
    public static final Map EMPTY_MAP = new FixedCollections.EmptyMap<Object, Object>();

    private static Random shuffler;

    private Collections() {
    }

    public static <T extends Comparable<? super T>> void sort(List<T> list) { list.sort(null); }
    public static <T> void sort(List<T> list, Comparator<? super T> c) { list.sort(c); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> int binarySearch(List<? extends Comparable<? super T>> list, T key) {
        return binarySearch((List) list, key, (Comparator) Support.NATURAL);
    }

    @SuppressWarnings("unchecked")
    public static <T> int binarySearch(List<? extends T> list, T key, Comparator<? super T> c) {
        Comparator<Object> order = c == null ? Support.NATURAL : (Comparator<Object>) c;
        boolean indexed = list instanceof RandomAccess || list.size() < SEARCH_INDEXED;
        ListIterator<? extends T> it = indexed ? null : list.listIterator();
        int lo = 0;
        int hi = list.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int r = order.compare(indexed ? list.get(mid) : moveTo(it, mid), key);
            if (r < 0) {
                lo = mid + 1;
            } else if (r > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -(lo + 1);
    }

    /** Steps `it` forward or back until it has just passed `index`, and returns that element. */
    private static <T> T moveTo(ListIterator<? extends T> it, int index) {
        T e;
        int pos = it.nextIndex();
        if (pos <= index) {
            do {
                e = it.next();
            } while (pos++ < index);
        } else {
            do {
                e = it.previous();
            } while (--pos > index);
        }
        return e;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void reverse(List<?> list) {
        int n = list.size();
        if (n < REVERSE_INDEXED || list instanceof RandomAccess) {
            for (int i = 0, j = n - 1; i < n / 2; i++, j--) {
                swap(list, i, j);
            }
            return;
        }
        ListIterator forward = list.listIterator();
        ListIterator backward = list.listIterator(n);
        for (int i = 0; i < n / 2; i++) {
            Object e = forward.next();
            forward.set(backward.previous());
            backward.set(e);
        }
    }

    public static void shuffle(List<?> list) {
        Random r = shuffler;
        if (r == null) {
            shuffler = r = new Random();
        }
        shuffle(list, r);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void shuffle(List<?> list, Random rnd) {
        int n = list.size();
        if (n < SHUFFLE_INDEXED || list instanceof RandomAccess) {
            for (int i = n; i > 1; i--) {
                swap(list, i - 1, rnd.nextInt(i));
            }
            return;
        }
        Object[] a = list.toArray();
        for (int i = n; i > 1; i--) {
            int j = rnd.nextInt(i);
            Object t = a[i - 1];
            a[i - 1] = a[j];
            a[j] = t;
        }
        ListIterator it = list.listIterator();
        for (Object e : a) {
            it.next();
            it.set(e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void swap(List<?> list, int i, int j) {
        List l = list;
        l.set(i, l.set(j, l.get(i)));
    }

    public static <T> void fill(List<? super T> list, T obj) {
        int n = list.size();
        if (n < FILL_INDEXED || list instanceof RandomAccess) {
            for (int i = 0; i < n; i++) {
                list.set(i, obj);
            }
            return;
        }
        for (ListIterator<? super T> it = list.listIterator(); it.hasNext();) {
            it.next();
            it.set(obj);
        }
    }

    public static <T> void copy(List<? super T> dest, List<? extends T> src) {
        int n = src.size();
        if (n > dest.size()) {
            throw new IndexOutOfBoundsException("Source does not fit in dest");
        }
        if (n < COPY_INDEXED || (src instanceof RandomAccess && dest instanceof RandomAccess)) {
            for (int i = 0; i < n; i++) {
                dest.set(i, src.get(i));
            }
            return;
        }
        ListIterator<? super T> d = dest.listIterator();
        ListIterator<? extends T> s = src.listIterator();
        for (int i = 0; i < n; i++) {
            d.next();
            d.set(s.next());
        }
    }

    public static <T extends Object & Comparable<? super T>> T min(Collection<? extends T> coll) {
        return best(coll, Support.NATURAL, -1);
    }

    public static <T> T min(Collection<? extends T> coll, Comparator<? super T> comp) { return best(coll, comp, -1); }

    public static <T extends Object & Comparable<? super T>> T max(Collection<? extends T> coll) {
        return best(coll, Support.NATURAL, 1);
    }

    public static <T> T max(Collection<? extends T> coll, Comparator<? super T> comp) { return best(coll, comp, 1); }

    /** Spec 8.C2: each next element is compared against the best so far; `sign` picks min or max. */
    @SuppressWarnings("unchecked")
    private static <T> T best(Collection<? extends T> coll, Comparator<? super T> comp, int sign) {
        Comparator<Object> order = comp == null ? Support.NATURAL : (Comparator<Object>) comp;
        Iterator<? extends T> it = coll.iterator();
        T best = it.next();
        while (it.hasNext()) {
            T e = it.next();
            if (Integer.signum(order.compare(e, best)) == sign) {
                best = e;
            }
        }
        return best;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void rotate(List<?> list, int distance) {
        List l = list;
        int n = l.size();
        if (n == 0) {
            return;
        }
        if (l instanceof RandomAccess || n < ROTATE_INDEXED) {
            int d = ((distance % n) + n) % n;
            if (d == 0) {
                return;
            }
            for (int start = 0, moved = 0; moved != n; start++) {
                Object displaced = l.get(start);
                int i = start;
                do {
                    i = (i + d) % n;
                    displaced = l.set(i, displaced);
                    moved++;
                } while (i != start);
            }
            return;
        }
        int mid = ((-distance % n) + n) % n;
        if (mid == 0) {
            return;
        }
        reverse(l.subList(0, mid));
        reverse(l.subList(mid, n));
        reverse(l);
    }

    public static <T> boolean replaceAll(List<T> list, T oldVal, T newVal) {
        boolean changed = false;
        int n = list.size();
        if (n < REPLACE_INDEXED || list instanceof RandomAccess) {
            for (int i = 0; i < n; i++) {
                if (Support.eq(oldVal, list.get(i))) {
                    list.set(i, newVal);
                    changed = true;
                }
            }
            return changed;
        }
        for (ListIterator<T> it = list.listIterator(); it.hasNext();) {
            if (Support.eq(oldVal, it.next())) {
                it.set(newVal);
                changed = true;
            }
        }
        return changed;
    }

    public static int indexOfSubList(List<?> source, List<?> target) { return SubListSearch.first(source, target); }
    public static int lastIndexOfSubList(List<?> source, List<?> target) { return SubListSearch.last(source, target); }

    public static int frequency(Collection<?> c, Object o) {
        int count = 0;
        for (Object e : c) {
            if (Support.eq(o, e)) {
                count++;
            }
        }
        return count;
    }

    // Spec 8.C2: which side is iterated and which is asked.
    public static boolean disjoint(Collection<?> c1, Collection<?> c2) {
        Collection<?> walk = c1;
        Collection<?> ask = c2;
        boolean sets = c1 instanceof Set || c2 instanceof Set;
        if (!sets && (c1.isEmpty() || c2.isEmpty())) {
            return true;
        }
        if (c1 instanceof Set || (!sets && c1.size() > c2.size())) {
            walk = c2;
            ask = c1;
        }
        for (Object e : walk) {
            if (ask.contains(e)) {
                return false;
            }
        }
        return true;
    }

    @SafeVarargs
    public static <T> boolean addAll(Collection<? super T> c, T... elements) {
        boolean changed = false;
        for (T e : elements) {
            changed |= c.add(e);
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    public static <T> Comparator<T> reverseOrder() {
        return (Comparator<T>) (Object) Comparators.ReverseNatural.INSTANCE;
    }

    @SuppressWarnings("unchecked")
    public static <T> Comparator<T> reverseOrder(Comparator<T> cmp) {
        if (cmp == null) {
            return reverseOrder();
        }
        if (cmp == (Object) Comparators.ReverseNatural.INSTANCE) {
            return (Comparator<T>) (Object) Comparators.NATURAL;
        }
        if (cmp instanceof Comparators.Reverse) {
            return ((Comparators.Reverse<T>) cmp).cmp;
        }
        return Comparators.reverse(cmp);
    }

    public static <T> Enumeration<T> enumeration(Collection<T> c) {
        Iterator<T> it = c.iterator();
        return new Enumeration<T>() {
            public boolean hasMoreElements() { return it.hasNext(); }
            public T nextElement() { return it.next(); }
        };
    }

    public static <T> ArrayList<T> list(Enumeration<T> e) {
        ArrayList<T> out = new ArrayList<>();
        while (e.hasMoreElements()) {
            out.add(e.nextElement());
        }
        return out;
    }

    public static <E> Set<E> newSetFromMap(Map<E, Boolean> map) { return new FixedCollections.MapBackedSet<E>(map); }
    public static <T> Queue<T> asLifoQueue(Deque<T> deque) { return new FixedCollections.LifoQueue<T>(deque); }

    // ---- factories (FixedCollections) ----

    public static <T> List<T> nCopies(int n, T o) {
        if (n < 0) {
            throw new IllegalArgumentException("List length = " + n);
        }
        return new FixedCollections.Copies<T>(n, o);
    }

    @SuppressWarnings("unchecked")
    public static final <T> List<T> emptyList() { return (List<T>) EMPTY_LIST; }

    @SuppressWarnings("unchecked")
    public static final <T> Set<T> emptySet() { return (Set<T>) EMPTY_SET; }

    @SuppressWarnings("unchecked")
    public static final <K, V> Map<K, V> emptyMap() { return (Map<K, V>) EMPTY_MAP; }
    public static <E> SortedSet<E> emptySortedSet() { return emptyNavigableSet(); }
    public static <E> NavigableSet<E> emptyNavigableSet() { return unmodifiableNavigableSet(new TreeSet<E>()); }
    public static final <K, V> SortedMap<K, V> emptySortedMap() { return emptyNavigableMap(); }

    public static final <K, V> NavigableMap<K, V> emptyNavigableMap() {
        return unmodifiableNavigableMap(new TreeMap<K, V>());
    }

    @SuppressWarnings("unchecked")
    public static <T> Iterator<T> emptyIterator() { return (Iterator<T>) FixedCollections.NO_ELEMENTS; }

    @SuppressWarnings("unchecked")
    public static <T> ListIterator<T> emptyListIterator() { return (ListIterator<T>) FixedCollections.NO_ELEMENTS; }

    @SuppressWarnings("unchecked")
    public static <T> Enumeration<T> emptyEnumeration() { return (Enumeration<T>) FixedCollections.NO_ELEMENTS; }
    public static <T> Set<T> singleton(T o) { return new FixedCollections.One<T>(o); }
    public static <T> List<T> singletonList(T o) { return new FixedCollections.OneList<T>(o); }

    public static <K, V> Map<K, V> singletonMap(K key, V value) {
        return new FixedCollections.OneMap<K, V>(key, value);
    }

    // ---- unmodifiable views (Unmodifiable) ----

    public static <T> Collection<T> unmodifiableCollection(Collection<? extends T> c) {
        return new Unmodifiable.Coll<T>(c);
    }

    public static <T> Set<T> unmodifiableSet(Set<? extends T> s) { return new Unmodifiable.USet<T>(s); }
    public static <T> SortedSet<T> unmodifiableSortedSet(SortedSet<T> s) { return new Unmodifiable.USortedSet<T>(s); }

    public static <T> NavigableSet<T> unmodifiableNavigableSet(NavigableSet<T> s) {
        return new Unmodifiable.UNavigableSet<T>(s);
    }

    public static <T> List<T> unmodifiableList(List<? extends T> list) { return Unmodifiable.list(list); }

    public static <K, V> Map<K, V> unmodifiableMap(Map<? extends K, ? extends V> m) {
        return new Unmodifiable.UMap<K, V>(m);
    }

    @SuppressWarnings("unchecked")
    public static <K, V> SortedMap<K, V> unmodifiableSortedMap(SortedMap<K, ? extends V> m) {
        return new Unmodifiable.USortedMap<K, V>((SortedMap<K, V>) m);
    }

    @SuppressWarnings("unchecked")
    public static <K, V> NavigableMap<K, V> unmodifiableNavigableMap(NavigableMap<K, ? extends V> m) {
        return new Unmodifiable.UNavigableMap<K, V>((NavigableMap<K, V>) m);
    }

    // ---- synchronized views (Synchronized) ----

    public static <T> Collection<T> synchronizedCollection(Collection<T> c) {
        return new Synchronized.SColl<T>(c, null);
    }

    public static <T> Set<T> synchronizedSet(Set<T> s) { return new Synchronized.SSet<T>(s, null); }

    public static <T> SortedSet<T> synchronizedSortedSet(SortedSet<T> s) {
        return new Synchronized.SSortedSet<T>(s, null);
    }

    public static <T> NavigableSet<T> synchronizedNavigableSet(NavigableSet<T> s) {
        return new Synchronized.SNavigableSet<T>(s, null);
    }

    public static <T> List<T> synchronizedList(List<T> list) { return Synchronized.list(list, null); }
    public static <K, V> Map<K, V> synchronizedMap(Map<K, V> m) { return new Synchronized.SMap<K, V>(m, null); }

    public static <K, V> SortedMap<K, V> synchronizedSortedMap(SortedMap<K, V> m) {
        return new Synchronized.SSortedMap<K, V>(m, null);
    }

    public static <K, V> NavigableMap<K, V> synchronizedNavigableMap(NavigableMap<K, V> m) {
        return new Synchronized.SNavigableMap<K, V>(m, null);
    }

    // ---- type-checked views (Checked) ----

    public static <E> Collection<E> checkedCollection(Collection<E> c, Class<E> type) {
        return new Checked.CColl<E>(c, type);
    }

    public static <E> Queue<E> checkedQueue(Queue<E> queue, Class<E> type) {
        return new Checked.CQueue<E>(queue, type);
    }

    public static <E> Set<E> checkedSet(Set<E> s, Class<E> type) { return new Checked.CSet<E>(s, type); }

    public static <E> SortedSet<E> checkedSortedSet(SortedSet<E> s, Class<E> type) {
        return new Checked.CSortedSet<E>(s, type);
    }

    public static <E> NavigableSet<E> checkedNavigableSet(NavigableSet<E> s, Class<E> type) {
        return new Checked.CNavigableSet<E>(s, type);
    }

    public static <E> List<E> checkedList(List<E> list, Class<E> type) { return Checked.list(list, type); }

    public static <K, V> Map<K, V> checkedMap(Map<K, V> m, Class<K> keyType, Class<V> valueType) {
        return new Checked.CMap<K, V>(m, keyType, valueType);
    }

    public static <K, V> SortedMap<K, V> checkedSortedMap(SortedMap<K, V> m, Class<K> keyType, Class<V> valueType) {
        return new Checked.CSortedMap<K, V>(m, keyType, valueType);
    }

    public static <K, V> NavigableMap<K, V> checkedNavigableMap(NavigableMap<K, V> m, Class<K> keyType,
            Class<V> valueType) {
        return new Checked.CNavigableMap<K, V>(m, keyType, valueType);
    }
}
