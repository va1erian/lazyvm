package java.util;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public class Vector<E> extends AbstractList<E> implements List<E>, RandomAccess, Cloneable, java.io.Serializable {
    protected Object[] elementData;
    protected int elementCount;
    protected int capacityIncrement;

    public Vector(int initialCapacity, int capacityIncrement) {
        Support.checkCapacity(initialCapacity);
        this.elementData = new Object[initialCapacity];
        this.capacityIncrement = capacityIncrement;
    }

    public Vector(int initialCapacity) { this(initialCapacity, 0); }

    // Spec 3.V1: the default capacity is 10.
    public Vector() { this(10); }

    public Vector(Collection<? extends E> c) {
        elementData = Support.plainArray(c.toArray());
        elementCount = elementData.length;
    }

    public synchronized void copyInto(Object[] anArray) { System.arraycopy(elementData, 0, anArray, 0, elementCount); }

    public synchronized void trimToSize() {
        modCount++;
        if (elementCount < elementData.length) {
            elementData = Arrays.copyOf(elementData, elementCount);
        }
    }

    public synchronized void ensureCapacity(int minCapacity) {
        if (minCapacity > 0) {
            modCount++;
            ensureRoom(minCapacity);
        }
    }

    // Spec 3.V1: grow by the increment, or double, and at least to what is needed.
    private void ensureRoom(int needed) {
        int capacity = elementData.length;
        if (needed > capacity) {
            int next = capacity + (capacityIncrement > 0 ? capacityIncrement : capacity);
            elementData = Arrays.copyOf(elementData, Math.max(next, needed));
        }
    }

    // Spec 9.F1: every setSize counts as a change.
    public synchronized void setSize(int newSize) {
        modCount++;
        if (newSize > elementCount) {
            ensureRoom(newSize);
        } else {
            for (int i = newSize; i < elementCount; i++) {
                elementData[i] = null;
            }
        }
        elementCount = newSize;
    }

    public synchronized int capacity() { return elementData.length; }
    public synchronized int size() { return elementCount; }
    public synchronized boolean isEmpty() { return elementCount == 0; }

    public Enumeration<E> elements() {
        return new Enumeration<E>() {
            int next;

            public boolean hasMoreElements() { return next < elementCount; }

            public E nextElement() {
                synchronized (Vector.this) {
                    if (next < elementCount) {
                        return at(next++);
                    }
                }
                throw new NoSuchElementException("Vector Enumeration");
            }
        };
    }

    public boolean contains(Object o) { return indexOf(o, 0) >= 0; }
    public int indexOf(Object o) { return indexOf(o, 0); }

    public synchronized int indexOf(Object o, int index) {
        return Support.indexOf(elementData, index, elementCount, o);
    }

    public synchronized int lastIndexOf(Object o) { return lastIndexOf(o, elementCount - 1); }

    public synchronized int lastIndexOf(Object o, int index) {
        if (index >= elementCount) {
            throw new IndexOutOfBoundsException(index + " >= " + elementCount);
        }
        return Support.lastIndexOf(elementData, index, o);
    }

    @SuppressWarnings("unchecked")
    private E at(int index) { return (E) elementData[index]; }

    // Spec 11: the legacy accessors say "i >= n"; a negative index fails in the array access.
    private void checkLegacy(int index) {
        if (index >= elementCount) {
            throw new ArrayIndexOutOfBoundsException(index + " >= " + elementCount);
        }
    }

    // Spec 11: the List accessors use the standard array message.
    private void checkListIndex(int index) {
        if (index >= elementCount) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
    }

    public synchronized E elementAt(int index) {
        checkLegacy(index);
        return at(index);
    }

    public synchronized E firstElement() {
        if (elementCount == 0) {
            throw new NoSuchElementException();
        }
        return at(0);
    }

    public synchronized E lastElement() {
        if (elementCount == 0) {
            throw new NoSuchElementException();
        }
        return at(elementCount - 1);
    }

    public synchronized void setElementAt(E obj, int index) {
        checkLegacy(index);
        elementData[index] = obj;
    }

    // Spec 9.F1: Vector's index removals and insertions count as a change even when the index is bad.
    public synchronized void removeElementAt(int index) {
        modCount++;
        checkLegacy(index);
        if (index < 0) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        removeSlots(index, index + 1);
    }

    public synchronized void insertElementAt(E obj, int index) {
        modCount++;
        if (index > elementCount) {
            throw new ArrayIndexOutOfBoundsException(index + " > " + elementCount);
        }
        ensureRoom(elementCount + 1);
        System.arraycopy(elementData, index, elementData, index + 1, elementCount - index);
        elementData[index] = obj;
        elementCount++;
    }

    public synchronized void addElement(E obj) {
        modCount++;
        ensureRoom(elementCount + 1);
        elementData[elementCount++] = obj;
    }

    // Spec 9.F1: removing counts as a change even when nothing is found.
    public synchronized boolean removeElement(Object obj) {
        modCount++;
        // Spec 8.C1: Vector asks its own (overridable) indexOf here.
        int i = indexOf(obj);
        if (i < 0) {
            return false;
        }
        removeSlots(i, i + 1);
        return true;
    }

    public synchronized void removeAllElements() {
        modCount++;
        Arrays.fill(elementData, 0, elementCount, null);
        elementCount = 0;
    }

    public synchronized Object clone() {
        try {
            @SuppressWarnings("unchecked")
            Vector<E> copy = (Vector<E>) super.clone();
            copy.elementData = Arrays.copyOf(elementData, elementCount);
            copy.modCount = 0;
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    public synchronized Object[] toArray() { return Arrays.copyOf(elementData, elementCount); }
    public synchronized <T> T[] toArray(T[] a) { return Support.toArray(elementData, elementCount, a); }

    public synchronized E get(int index) {
        checkListIndex(index);
        return at(index);
    }

    public synchronized E set(int index, E element) {
        checkListIndex(index);
        E old = at(index);
        elementData[index] = element;
        return old;
    }

    public synchronized boolean add(E e) {
        addElement(e);
        return true;
    }

    public boolean remove(Object o) { return removeElement(o); }
    public void add(int index, E element) { insertElementAt(element, index); }

    public synchronized E remove(int index) {
        modCount++;
        checkListIndex(index);
        E old = at(index);
        removeSlots(index, index + 1);
        return old;
    }

    public void clear() { removeAllElements(); }
    public synchronized boolean containsAll(Collection<?> c) { return super.containsAll(c); }
    public synchronized boolean addAll(Collection<? extends E> c) { return insertAll(elementCount, c); }
    public synchronized boolean addAll(int index, Collection<? extends E> c) { return insertAll(index, c); }

    // Spec 8.C1: Vector removes through its iterator, one remove(i) per element that leaves.
    public synchronized boolean removeAll(Collection<?> c) { return super.removeAll(c); }
    public synchronized boolean retainAll(Collection<?> c) { return super.retainAll(c); }

    // Spec 9.F1: adding an empty collection still counts as a change.
    private boolean insertAll(int index, Collection<? extends E> c) {
        modCount++;
        if (index < 0 || index > elementCount) {
            throw new ArrayIndexOutOfBoundsException(index);
        }
        Object[] more = c.toArray();
        ensureRoom(elementCount + more.length);
        System.arraycopy(elementData, index, elementData, index + more.length, elementCount - index);
        System.arraycopy(more, 0, elementData, index, more.length);
        elementCount += more.length;
        return more.length != 0;
    }

    public synchronized boolean equals(Object o) { return super.equals(o); }
    public synchronized int hashCode() { return super.hashCode(); }
    public synchronized String toString() { return super.toString(); }
    public synchronized List<E> subList(int fromIndex, int toIndex) { return super.subList(fromIndex, toIndex); }

    protected synchronized void removeRange(int fromIndex, int toIndex) {
        modCount++;
        removeSlots(fromIndex, toIndex);
    }

    private void removeSlots(int from, int to) {
        System.arraycopy(elementData, to, elementData, from, elementCount - to);
        int newCount = elementCount - (to - from);
        Arrays.fill(elementData, newCount, elementCount, null);
        elementCount = newCount;
    }

    public synchronized ListIterator<E> listIterator(int index) { return indexOnlyListIterator(index, elementCount); }
    public synchronized ListIterator<E> listIterator() { return indexOnlyListIterator(0, elementCount); }
    public synchronized Iterator<E> iterator() { return super.iterator(); }

    @SuppressWarnings("unchecked")
    public synchronized void forEach(Consumer<? super E> action) {
        Support.forEach(this, elementData, elementCount, (Consumer<Object>) action);
    }

    @SuppressWarnings("unchecked")
    public synchronized boolean removeIf(Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        int kept = Support.sweep(this, elementData, elementCount, (Predicate<Object>) filter);
        if (kept == elementCount) {
            return false;
        }
        elementCount = kept;
        modCount++;
        return true;
    }

    @SuppressWarnings("unchecked")
    public synchronized void replaceAll(UnaryOperator<E> operator) {
        Support.replaceAll(this, elementData, elementCount, (UnaryOperator<Object>) operator);
        modCount++;
    }

    // Spec 7.S2: sorts its own storage, then checks for and bumps the modification count.
    @SuppressWarnings("unchecked")
    public synchronized void sort(Comparator<? super E> c) {
        int expected = modCount;
        Arrays.sort((E[]) elementData, 0, elementCount, c);
        if (modCount != expected) {
            throw new ConcurrentModificationException();
        }
        modCount++;
    }

    int remainingStyle() { return 1; }

    // Spec 9.F2: forEachRemaining reads the storage as it was when the walk began; the walk stays
    // below the count taken then, which never exceeds the storage's length.
    Object[] walkStorage(int i) { return elementData; }

    // Spec 8.C1: the iterators read the storage and the count, not an overriding get or size.
    E cursorGet(int i) {
        checkListIndex(i);
        return at(i);
    }

    int cursorLimit() { return elementCount; }
}
