package stream;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * When a spliterator over a collection reads the size and creates its iterator, what a later
 * change does, and what forEachRemaining delivers before a ConcurrentModificationException.
 */
public class CollectionBinding {
    static void t(String label, Supplier<Object> s) {
        Object r;
        try { r = s.get(); } catch (Throwable e) { r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); }
        System.out.println(label + ": " + r);
    }
    static String names(int c) {
        String[] n = {"ORDERED", "DISTINCT", "SORTED", "SIZED", "NONNULL", "IMMUTABLE", "CONCURRENT", "SUBSIZED"};
        int[] v = {Spliterator.ORDERED, Spliterator.DISTINCT, Spliterator.SORTED, Spliterator.SIZED, Spliterator.NONNULL, Spliterator.IMMUTABLE, Spliterator.CONCURRENT, Spliterator.SUBSIZED};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length; i++) if ((c & v[i]) != 0) sb.append(sb.length() == 0 ? "" : "|").append(n[i]);
        return sb.length() == 0 ? "-" : sb.toString();
    }
    static Map<String, Supplier<Collection<Integer>>> kinds() {
        Map<String, Supplier<Collection<Integer>>> m = new LinkedHashMap<>();
        m.put("ArrayList", () -> new ArrayList<>(Arrays.asList(1, 2, 3)));
        m.put("LinkedList", () -> new LinkedList<>(Arrays.asList(1, 2, 3)));
        m.put("Vector", () -> new Vector<>(Arrays.asList(1, 2, 3)));
        m.put("HashSet", () -> new HashSet<>(Arrays.asList(1, 2, 3)));
        m.put("LinkedHashSet", () -> new LinkedHashSet<>(Arrays.asList(1, 2, 3)));
        m.put("TreeSet", () -> new TreeSet<>(Arrays.asList(1, 2, 3)));
        m.put("ArrayDeque", () -> new ArrayDeque<>(Arrays.asList(1, 2, 3)));
        m.put("PriorityQueue", () -> new PriorityQueue<>(Arrays.asList(1, 2, 3)));
        m.put("CopyOnWriteArrayList", () -> new CopyOnWriteArrayList<>(Arrays.asList(1, 2, 3)));
        m.put("ConcurrentLinkedQueue", () -> new ConcurrentLinkedQueue<>(Arrays.asList(1, 2, 3)));
        m.put("LinkedBlockingQueue", () -> new LinkedBlockingQueue<>(Arrays.asList(1, 2, 3)));
        return m;
    }
    static String drain(Spliterator<Integer> sp) { StringBuilder sb = new StringBuilder(); sp.forEachRemaining(x -> sb.append(x).append(',')); return sb.toString(); }

    public static void main(String[] args) {
        for (Map.Entry<String, Supplier<Collection<Integer>>> k : kinds().entrySet()) {
            String n = k.getKey();
            Supplier<Collection<Integer>> mk = k.getValue();
            t(n + " add before estimate", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = Spliterators.spliterator(c, 0); c.add(4); return sp.estimateSize() + " " + names(sp.characteristics()); });
            t(n + " add after estimate", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = Spliterators.spliterator(c, 0); long e = sp.estimateSize(); c.add(4); return e + " " + sp.estimateSize(); });
            t(n + " add after characteristics", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = Spliterators.spliterator(c, 0); sp.characteristics(); c.add(4); return sp.estimateSize(); });
            t(n + " add after exact size", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = Spliterators.spliterator(c, 0); long e = sp.getExactSizeIfKnown(); c.add(4); return e + " " + sp.getExactSizeIfKnown(); });
            t(n + " add before tryAdvance", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = Spliterators.spliterator(c, 0); c.add(4); StringBuilder sb = new StringBuilder(); while (sp.tryAdvance(x -> sb.append(x).append(','))) { } return sb; });
            t(n + " add between tryAdvance", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = Spliterators.spliterator(c, 0); StringBuilder sb = new StringBuilder(); sp.tryAdvance(x -> sb.append(x).append(',')); c.add(4); while (sp.tryAdvance(x -> sb.append(x).append(','))) { } return sb; });
            t(n + " add after estimate then tryAdvance", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = Spliterators.spliterator(c, 0); sp.estimateSize(); c.add(4); StringBuilder sb = new StringBuilder(); while (sp.tryAdvance(x -> sb.append(x).append(','))) { } return sb; });
            t(n + " add after estimate then forEachRemaining", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = Spliterators.spliterator(c, 0); sp.estimateSize(); c.add(4); StringBuilder sb = new StringBuilder(); try { sp.forEachRemaining(x -> sb.append(x).append(',')); } catch (ConcurrentModificationException e) { sb.append("CME"); } return sb; });
            t(n + " add inside forEachRemaining", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = Spliterators.spliterator(c, 0); StringBuilder sb = new StringBuilder(); try { sp.forEachRemaining(x -> { sb.append(x).append(','); if (x == 1) c.add(9); }); sb.append("done"); } catch (ConcurrentModificationException e) { sb.append("CME"); } return sb; });
            t(n + " remove inside forEachRemaining", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = Spliterators.spliterator(c, 0); StringBuilder sb = new StringBuilder(); try { sp.forEachRemaining(x -> { sb.append(x).append(','); if (x == 1) c.remove(3); }); sb.append("done"); } catch (ConcurrentModificationException e) { sb.append("CME"); } return sb; });
            t(n + " stream iterator forEachRemaining add", () -> { Collection<Integer> c = mk.get(); Iterator<Integer> it = c.stream().iterator(); StringBuilder sb = new StringBuilder(); try { it.forEachRemaining(x -> { sb.append(x).append(','); if (x == 1) c.add(9); }); sb.append("done"); } catch (ConcurrentModificationException e) { sb.append("CME"); } return sb; });
            t(n + " spliterator forEachRemaining add", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = c.spliterator(); StringBuilder sb = new StringBuilder(); try { sp.forEachRemaining(x -> { sb.append(x).append(','); if (x == 1) c.add(9); }); sb.append("done"); } catch (ConcurrentModificationException e) { sb.append("CME"); } return sb; });
            t(n + " spliterator tryAdvance add", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = c.spliterator(); StringBuilder sb = new StringBuilder(); try { sp.tryAdvance(x -> { sb.append(x).append(','); c.add(9); }); while (sp.tryAdvance(x -> sb.append(x).append(','))) { } sb.append("done"); } catch (ConcurrentModificationException e) { sb.append("CME"); } return sb; });
            t(n + " spliterator add before first use", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = c.spliterator(); c.add(9); StringBuilder sb = new StringBuilder(); try { sp.forEachRemaining(x -> sb.append(x).append(',')); sb.append("done"); } catch (ConcurrentModificationException e) { sb.append("CME"); } return sb; });
            t(n + " spliterator add after estimate", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = c.spliterator(); long e = sp.estimateSize(); c.add(9); StringBuilder sb = new StringBuilder(e + " "); try { sp.forEachRemaining(x -> sb.append(x).append(',')); sb.append("done"); } catch (ConcurrentModificationException ex) { sb.append("CME"); } return sb; });
            t(n + " spliterator add after characteristics", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = c.spliterator(); sp.characteristics(); c.add(9); StringBuilder sb = new StringBuilder(); try { sp.forEachRemaining(x -> sb.append(x).append(',')); sb.append("done"); } catch (ConcurrentModificationException ex) { sb.append("CME"); } return sb; });
            t(n + " spliterator add after exact size", () -> { Collection<Integer> c = mk.get(); Spliterator<Integer> sp = c.spliterator(); long e = sp.getExactSizeIfKnown(); c.add(9); StringBuilder sb = new StringBuilder(e + " "); try { sp.forEachRemaining(x -> sb.append(x).append(',')); sb.append("done"); } catch (ConcurrentModificationException ex) { sb.append("CME"); } return sb; });
            t(n + " stream add after peek of first", () -> { Collection<Integer> c = mk.get(); StringBuilder sb = new StringBuilder(); try { c.stream().peek(x -> { if (x == 1) c.add(9); }).forEach(x -> sb.append(x).append(',')); sb.append("done"); } catch (ConcurrentModificationException ex) { sb.append("CME"); } return sb; });
            t(n + " stream add in last peek", () -> { Collection<Integer> c = mk.get(); StringBuilder sb = new StringBuilder(); try { c.stream().peek(x -> { if (x == 3) c.add(9); }).forEach(x -> sb.append(x).append(',')); sb.append("done"); } catch (ConcurrentModificationException ex) { sb.append("CME"); } return sb; });
            t(n + " stream remove in peek", () -> { Collection<Integer> c = mk.get(); StringBuilder sb = new StringBuilder(); try { c.stream().peek(x -> { if (x == 1) c.remove(2); }).forEach(x -> sb.append(x).append(',')); sb.append("done"); } catch (ConcurrentModificationException ex) { sb.append("CME"); } return sb; });
        }

        // the size handed to Spliterators.spliterator(Collection) is the size at first use
        t("Collection spliterator flags", () -> names(Spliterators.spliterator(new ArrayList<>(Arrays.asList(1, 2)), Spliterator.ORDERED).characteristics()));
        t("Collection spliterator flags SORTED", () -> names(Spliterators.spliterator(new ArrayList<>(Arrays.asList(1, 2)), Spliterator.ORDERED | Spliterator.SORTED).characteristics()));
        t("Collection spliterator empty then add", () -> { List<Integer> c = new ArrayList<>(); Spliterator<Integer> sp = Spliterators.spliterator(c, 0); c.add(1); c.add(2); return sp.estimateSize() + " " + drain(sp); });
        t("Collection spliterator null", () -> Spliterators.spliterator((Collection<Integer>) null, 0));
        t("Collection spliterator split after add", () -> { List<Integer> c = new ArrayList<>(Arrays.asList(1, 2, 3, 4)); Spliterator<Integer> sp = Spliterators.spliterator(c, 0); c.add(5); Spliterator<Integer> p = sp.trySplit(); return (p == null ? "null" : drain(p)) + "|" + drain(sp); });
        t("Collection spliterator iterator() call count", () -> {
            int[] n = {0};
            Collection<Integer> c = new AbstractCollection<Integer>() {
                public Iterator<Integer> iterator() { n[0]++; return Arrays.asList(1, 2).iterator(); }
                public int size() { n[0] += 10; return 2; }
            };
            Spliterator<Integer> sp = Spliterators.spliterator(c, 0);
            int created = n[0];
            sp.estimateSize(); int afterEst = n[0];
            sp.tryAdvance(x -> {}); int afterAdvance = n[0];
            sp.tryAdvance(x -> {}); sp.estimateSize();
            return created + " " + afterEst + " " + afterAdvance + " " + n[0];
        });
        t("Collection spliterator order estimate then iterator", () -> {
            StringBuilder log = new StringBuilder();
            Collection<Integer> c = new AbstractCollection<Integer>() {
                public Iterator<Integer> iterator() { log.append("iterator "); return Arrays.asList(1, 2).iterator(); }
                public int size() { log.append("size "); return 2; }
            };
            Spliterator<Integer> sp = Spliterators.spliterator(c, 0);
            sp.tryAdvance(x -> {}); sp.tryAdvance(x -> {}); sp.tryAdvance(x -> {});
            return log;
        });
        t("Collection spliterator order characteristics", () -> {
            StringBuilder log = new StringBuilder();
            Collection<Integer> c = new AbstractCollection<Integer>() {
                public Iterator<Integer> iterator() { log.append("iterator "); return Arrays.asList(1, 2).iterator(); }
                public int size() { log.append("size "); return 2; }
            };
            Spliterator<Integer> sp = Spliterators.spliterator(c, 0);
            sp.characteristics(); log.append("| "); sp.getExactSizeIfKnown(); log.append("| "); sp.forEachRemaining(x -> {});
            return log;
        });
        t("Collection default spliterator order", () -> {
            StringBuilder log = new StringBuilder();
            Collection<Integer> c = new AbstractCollection<Integer>() {
                public Iterator<Integer> iterator() { log.append("iterator "); return Arrays.asList(1, 2).iterator(); }
                public int size() { log.append("size "); return 2; }
            };
            Spliterator<Integer> sp = c.spliterator();
            log.append("| "); sp.estimateSize(); log.append("| "); sp.tryAdvance(x -> {});
            return log;
        });
        t("Collection stream order", () -> {
            StringBuilder log = new StringBuilder();
            Collection<Integer> c = new AbstractCollection<Integer>() {
                public Iterator<Integer> iterator() { log.append("iterator "); return Arrays.asList(1, 2).iterator(); }
                public int size() { log.append("size "); return 2; }
            };
            Stream<Integer> s = c.stream();
            log.append("| "); s.count(); log.append("| ");
            c.stream().toArray();
            return log;
        });
    }
}
