package java.util;

import java.util.function.Consumer;

/**
 * A spliterator over a collection that touches the collection only when it is first used: the first look
 * at the size, the first element or a split asks the collection for its iterator, and then for its size
 * (docs/design/streams.md SP11). What the iterator does after a later change is the collection's business,
 * so the fail-fast behaviour of each collection shows through.
 */
final class Late<T> extends Spliterators.AbstractSpliterator<T> {
    private final Collection<? extends T> source;
    private final int ch;
    private final Comparator<? super T> order;
    private Iterator<? extends T> it;

    Late(Collection<? extends T> source, int ch, Comparator<? super T> order) {
        super(0, 0);
        this.source = source;
        this.ch = ch;
        this.order = order;
    }

    private Iterator<? extends T> bound() {
        if (it == null) {
            it = source.iterator();
            remaining = source.size();
        }
        return it;
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
        Objects.requireNonNull(action);
        Iterator<? extends T> i = bound();
        if (!i.hasNext()) {
            return false;
        }
        action.accept(i.next());
        return true;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void forEachRemaining(Consumer<? super T> action) {
        Objects.requireNonNull(action);
        ((Iterator) bound()).forEachRemaining(action);
    }

    @Override
    public Spliterator<T> trySplit() {
        bound();
        return super.trySplit();
    }

    @Override
    public long estimateSize() {
        bound();
        return remaining;
    }

    @Override
    public int characteristics() { return ch; }

    @Override
    public Comparator<? super T> getComparator() {
        if ((ch & SORTED) != 0) {
            return order;
        }
        throw new IllegalStateException();
    }
}
