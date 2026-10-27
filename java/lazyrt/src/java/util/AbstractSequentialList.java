package java.util;

public abstract class AbstractSequentialList<E> extends AbstractList<E> {
    protected AbstractSequentialList() {
    }

    public E get(int index) {
        try {
            return listIterator(index).next();
        } catch (NoSuchElementException e) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
    }

    public E set(int index, E element) {
        try {
            ListIterator<E> it = listIterator(index);
            E old = it.next();
            it.set(element);
            return old;
        } catch (NoSuchElementException e) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
    }

    public void add(int index, E element) { listIterator(index).add(element); }

    public E remove(int index) {
        try {
            ListIterator<E> it = listIterator(index);
            E old = it.next();
            it.remove();
            return old;
        } catch (NoSuchElementException e) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
    }

    public boolean addAll(int index, Collection<? extends E> c) {
        boolean changed = false;
        ListIterator<E> it = listIterator(index);
        for (E e : c) {
            it.add(e);
            changed = true;
        }
        return changed;
    }

    public Iterator<E> iterator() { return listIterator(); }

    public abstract ListIterator<E> listIterator(int index);
}
