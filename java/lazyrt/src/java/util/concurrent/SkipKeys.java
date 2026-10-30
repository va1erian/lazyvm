package java.util.concurrent;

import java.util.AbstractSet;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableSet;

/** The key view of a {@link SkipView}: reads and removes go through it, and nothing can be added. */
final class SkipKeys<K> extends AbstractSet<K> implements NavigableSet<K> {
    private final ConcurrentNavigableMap<K, ?> map;

    SkipKeys(ConcurrentNavigableMap<K, ?> map) {
        this.map = map;
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return map.containsKey(o);
    }

    @Override
    public boolean remove(Object o) {
        return map.remove(o) != null;
    }

    @Override
    public void clear() {
        map.clear();
    }

    @Override
    public Iterator<K> iterator() {
        return ((SkipView<K, ?>) map).keyWalk();
    }

    public Iterator<K> descendingIterator() {
        return descendingSet().iterator();
    }

    public K lower(K e) {
        return map.lowerKey(e);
    }

    public K floor(K e) {
        return map.floorKey(e);
    }

    public K ceiling(K e) {
        return map.ceilingKey(e);
    }

    public K higher(K e) {
        return map.higherKey(e);
    }

    public K pollFirst() {
        return ConcurrentSkipListMap.keyOf((Map.Entry<K, ?>) map.pollFirstEntry());
    }

    public K pollLast() {
        return ConcurrentSkipListMap.keyOf((Map.Entry<K, ?>) map.pollLastEntry());
    }

    public Comparator<? super K> comparator() {
        return map.comparator();
    }

    public K first() {
        return map.firstKey();
    }

    public K last() {
        return map.lastKey();
    }

    public NavigableSet<K> subSet(K fromElement, boolean fromInclusive, K toElement, boolean toInclusive) {
        return new SkipKeys<>(map.subMap(fromElement, fromInclusive, toElement, toInclusive));
    }

    public NavigableSet<K> headSet(K toElement, boolean inclusive) {
        return new SkipKeys<>(map.headMap(toElement, inclusive));
    }

    public NavigableSet<K> tailSet(K fromElement, boolean inclusive) {
        return new SkipKeys<>(map.tailMap(fromElement, inclusive));
    }

    public NavigableSet<K> subSet(K fromElement, K toElement) {
        return subSet(fromElement, true, toElement, false);
    }

    public NavigableSet<K> headSet(K toElement) {
        return headSet(toElement, false);
    }

    public NavigableSet<K> tailSet(K fromElement) {
        return tailSet(fromElement, true);
    }

    public NavigableSet<K> descendingSet() {
        return new SkipKeys<>(map.descendingMap());
    }
}
