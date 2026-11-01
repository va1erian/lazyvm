package lazy.internal;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;

/**
 * The spliterator of a concurrent collection: it follows the collection's own weakly consistent iterator,
 * which is made at the first step, and it reports an estimate but never an exact size.
 */
public final class WeakSplit<T> extends Spliterators.AbstractSpliterator<T> {
    private final Iterable<? extends T> src;
    private final Comparator<? super T> cmp;
    private Iterator<? extends T> it;

    public WeakSplit(Iterable<? extends T> src, long estimate, int characteristics, Comparator<? super T> cmp) {
        super(estimate, characteristics);
        this.src = src;
        this.cmp = cmp;
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
        Objects.requireNonNull(action);
        if (it == null) {
            it = src.iterator();
        }
        if (it.hasNext()) {
            action.accept(it.next());
            return true;
        }
        return false;
    }

    @Override
    public Comparator<? super T> getComparator() {
        if (hasCharacteristics(Spliterator.SORTED)) {
            return cmp;
        }
        throw new IllegalStateException();
    }
}
