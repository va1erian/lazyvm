package java.util;

public class Stack<E> extends Vector<E> {
    public Stack() {
    }

    public E push(E item) {
        addElement(item);
        return item;
    }

    public synchronized E pop() {
        E top = peek();
        removeElementAt(size() - 1);
        return top;
    }

    public synchronized E peek() {
        int n = size();
        if (n == 0) {
            throw new EmptyStackException();
        }
        return elementAt(n - 1);
    }

    public boolean empty() { return size() == 0; }

    /** The 1-based distance from the top of the stack to the topmost equal element, or -1. */
    public synchronized int search(Object o) {
        int i = lastIndexOf(o);
        return i >= 0 ? size() - i : -1;
    }
}
