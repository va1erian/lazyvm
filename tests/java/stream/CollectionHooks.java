package stream;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** How collections and arrays hand out streams: binding time, concurrent modification, order. */
public class CollectionHooks {
    static void t(String label, Supplier<Object> s) {
        try { System.out.println(label + ": " + s.get()); }
        catch (Throwable e) { System.out.println(label + ": " + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage())); }
    }
    @SafeVarargs static <T extends Collection<Integer>> T of(T c, Integer... xs) { c.addAll(Arrays.asList(xs)); return c; }

    /** Streams c and adds an element on every visit; reports how many visits ran and how it ended. */
    static String grow(Collection<Integer> c) {
        int[] seen = {0};
        String end;
        try { c.stream().forEach(x -> { seen[0]++; if (seen[0] == 1) c.add(100); }); end = "done"; }
        catch (ConcurrentModificationException e) { end = "CME"; }
        return seen[0] + " " + end + " " + c.size();
    }
    static String shrink(Collection<Integer> c) {
        int[] seen = {0};
        String end;
        try { c.stream().forEach(x -> { seen[0]++; if (seen[0] == 1) c.remove(2); }); end = "done"; }
        catch (ConcurrentModificationException e) { end = "CME"; }
        return seen[0] + " " + end + " " + c.size();
    }
    static String ops(Collection<Integer> c) {
        return grow(of(c, 1, 2, 3, 4)) + " | " + shrink(of(newLike(c), 1, 2, 3, 4));
    }
    @SuppressWarnings("unchecked") static Collection<Integer> newLike(Collection<Integer> c) {
        try { return (Collection<Integer>) c.getClass().newInstance(); } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static void main(String[] args) {
        // binding time
        t("late binding ArrayList", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2); Stream<Integer> s = l.stream(); l.add(3); return s.collect(Collectors.toList()); });
        t("late binding LinkedList", () -> { List<Integer> l = of(new LinkedList<>(), 1, 2); Stream<Integer> s = l.stream(); l.add(3); return s.collect(Collectors.toList()); });
        t("late binding HashSet", () -> { Set<Integer> l = of(new HashSet<>(), 1, 2); Stream<Integer> s = l.stream(); l.add(3); return s.sorted().collect(Collectors.toList()); });
        t("late binding TreeSet", () -> { Set<Integer> l = of(new TreeSet<>(), 1, 2); Stream<Integer> s = l.stream(); l.add(3); return s.collect(Collectors.toList()); });
        t("late binding ArrayDeque", () -> { Deque<Integer> l = of(new ArrayDeque<>(), 1, 2); Stream<Integer> s = l.stream(); l.add(3); return s.collect(Collectors.toList()); });
        t("late binding PriorityQueue", () -> { Queue<Integer> l = of(new PriorityQueue<>(), 1, 2); Stream<Integer> s = l.stream(); l.add(3); return s.sorted().collect(Collectors.toList()); });
        t("late binding Vector", () -> { List<Integer> l = of(new Vector<>(), 1, 2); Stream<Integer> s = l.stream(); l.add(3); return s.collect(Collectors.toList()); });
        t("late binding map keySet", () -> { Map<Integer, Integer> m = new TreeMap<>(); m.put(1, 1); Stream<Integer> s = m.keySet().stream(); m.put(2, 2); return s.collect(Collectors.toList()); });
        t("late binding map values", () -> { Map<Integer, Integer> m = new LinkedHashMap<>(); m.put(1, 1); Stream<Integer> s = m.values().stream(); m.put(2, 2); return s.collect(Collectors.toList()); });
        t("late binding Arrays.asList", () -> { Integer[] a = {1, 2}; List<Integer> l = Arrays.asList(a); Stream<Integer> s = l.stream(); a[0] = 9; return s.collect(Collectors.toList()); });
        t("late binding Arrays.stream", () -> { int[] a = {1, 2}; IntStream s = Arrays.stream(a); a[0] = 9; return Arrays.toString(s.toArray()); });
        t("late binding Arrays.stream objects", () -> { String[] a = {"x", "y"}; Stream<String> s = Arrays.stream(a); a[0] = "z"; return s.collect(Collectors.toList()); });
        t("late binding Stream.of array", () -> { String[] a = {"x", "y"}; Stream<String> s = Stream.of(a); a[1] = "z"; return s.collect(Collectors.toList()); });
        t("late binding subList", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2, 3, 4); Stream<Integer> s = l.subList(1, 3).stream(); l.set(1, 20); return s.collect(Collectors.toList()); });
        t("late binding subList structural", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2, 3, 4); List<Integer> sub = l.subList(1, 3); Stream<Integer> s = sub.stream(); sub.add(99); return s.collect(Collectors.toList()); });
        t("late binding emptied", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2); Stream<Integer> s = l.stream(); l.clear(); return s.count(); });
        t("bound after first element", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2, 3); Iterator<Integer> it = l.stream().iterator(); it.next(); l.add(4); try { return it.next() + " " + it.hasNext(); } catch (ConcurrentModificationException e) { return "CME"; } });
        t("late binding stream of collection", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2); Stream<Integer> s = Stream.of(l).flatMap(Collection::stream); l.add(3); return s.collect(Collectors.toList()); });
        t("each stream() is independent", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2); return l.stream().count() + l.stream().count(); });
        t("stream of empty", () -> new ArrayList<Integer>().stream().count());
        t("parallelStream", () -> of(new ArrayList<>(), 1, 2).parallelStream().isParallel());
        t("parallelStream late", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2); Stream<Integer> s = l.parallelStream(); l.add(3); return s.collect(Collectors.toList()); });
        t("parallelStream HashSet", () -> of(new HashSet<>(), 5, 6, 7).parallelStream().map(x -> x * 2).sorted().collect(Collectors.toList()));
        t("stream element order ArrayList", () -> of(new ArrayList<>(), 3, 1, 2).stream().collect(Collectors.toList()));
        t("stream element order PriorityQueue", () -> of(new PriorityQueue<>(), 3, 1, 2).stream().sorted().collect(Collectors.toList()));
        t("stream element order TreeSet", () -> of(new TreeSet<>(), 3, 1, 2).stream().collect(Collectors.toList()));
        t("stream element order descending", () -> of(new TreeSet<>(), 3, 1, 2).descendingSet().stream().collect(Collectors.toList()));
        t("stream element order LinkedHashSet", () -> of(new LinkedHashSet<>(), 3, 1, 2).stream().collect(Collectors.toList()));
        t("stream element order ArrayDeque", () -> { ArrayDeque<Integer> d = new ArrayDeque<>(); d.addFirst(1); d.addLast(2); d.addFirst(0); return d.stream().collect(Collectors.toList()); });
        t("stream of Vector", () -> of(new Vector<>(), 3, 1, 2).stream().collect(Collectors.toList()));
        t("stream of Stack", () -> { Stack<Integer> s = new Stack<>(); s.push(1); s.push(2); return s.stream().collect(Collectors.toList()); });
        t("stream of unmodifiable", () -> Collections.unmodifiableList(of(new ArrayList<>(), 1, 2)).stream().map(x -> x + 1).collect(Collectors.toList()));
        t("stream of singleton", () -> Collections.singletonList(5).stream().collect(Collectors.toList()));
        t("stream of nCopies", () -> Collections.nCopies(3, "x").stream().collect(Collectors.joining()));
        t("stream of emptyList", () -> Collections.emptyList().stream().count());
        t("stream of EnumSet", () -> EnumSet.of(Thread.State.WAITING, Thread.State.NEW).stream().collect(Collectors.toList()));
        t("stream of EnumMap values", () -> { EnumMap<Thread.State, Integer> m = new EnumMap<>(Thread.State.class); m.put(Thread.State.WAITING, 2); m.put(Thread.State.NEW, 1); return m.values().stream().collect(Collectors.toList()); });
        t("stream of copyOnWrite", () -> { CopyOnWriteArrayList<Integer> l = of(new CopyOnWriteArrayList<>(), 1, 2, 3); Stream<Integer> s = l.stream(); l.add(4); return s.collect(Collectors.toList()); });
        t("stream of copyOnWrite modify during", () -> { CopyOnWriteArrayList<Integer> l = of(new CopyOnWriteArrayList<>(), 1, 2, 3); StringBuilder sb = new StringBuilder(); l.stream().forEach(x -> { sb.append(x); l.add(9); }); return sb + " " + l.size(); });
        t("stream of ConcurrentHashMap modify during", () -> { ConcurrentHashMap<Integer, Integer> m = new ConcurrentHashMap<>(); m.put(1, 1); m.put(2, 2); int[] n = {0}; m.keySet().stream().forEach(x -> { n[0]++; m.put(x + 10, 0); }); return n[0] >= 2; });
        t("stream of ConcurrentLinkedQueue modify during", () -> { ConcurrentLinkedQueue<Integer> q = of(new ConcurrentLinkedQueue<>(), 1, 2, 3); StringBuilder sb = new StringBuilder(); q.stream().forEach(x -> { sb.append(x); if (x == 1) q.add(4); }); return sb.toString(); });
        t("stream of LinkedBlockingQueue", () -> of(new LinkedBlockingQueue<>(), 1, 2, 3).stream().map(x -> x * 2).collect(Collectors.toList()));

        // concurrent modification while streaming: how many elements were seen, and how it ended
        t("grow ArrayList", () -> ops(new ArrayList<>()));
        t("grow LinkedList", () -> ops(new LinkedList<>()));
        t("grow Vector", () -> ops(new Vector<>()));
        t("grow ArrayDeque", () -> ops(new ArrayDeque<>()));
        t("grow HashSet", () -> ops(new HashSet<>()));
        t("grow LinkedHashSet", () -> ops(new LinkedHashSet<>()));
        t("grow TreeSet", () -> ops(new TreeSet<>()));
        t("grow PriorityQueue", () -> ops(new PriorityQueue<>()));
        t("grow CopyOnWriteArrayList", () -> ops(new CopyOnWriteArrayList<>()));
        t("grow ConcurrentLinkedQueue", () -> ops(new ConcurrentLinkedQueue<>()));
        t("grow after the last element", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2, 3); int[] n = {0}; try { l.stream().forEach(x -> { if (++n[0] == 3) l.add(9); }); return "done"; } catch (ConcurrentModificationException e) { return "CME " + n[0]; } });
        t("modify in map then collect", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2, 3); try { return l.stream().map(x -> { if (x == 1) l.add(7); return x; }).collect(Collectors.toList()); } catch (ConcurrentModificationException e) { return "CME"; } });
        t("modify in anyMatch", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2, 3); try { return l.stream().anyMatch(x -> { if (x == 1) l.add(7); return x == 2; }); } catch (ConcurrentModificationException e) { return "CME"; } });
        t("modify in findFirst", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2, 3); try { return l.stream().peek(x -> l.add(7)).findFirst(); } catch (ConcurrentModificationException e) { return "CME"; } });
        t("modify in limit", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2, 3); try { return l.stream().peek(x -> { if (x == 1) l.add(7); }).limit(2).collect(Collectors.toList()); } catch (ConcurrentModificationException e) { return "CME"; } });
        t("modify in sorted", () -> { List<Integer> l = of(new ArrayList<>(), 3, 2, 1); try { return l.stream().sorted().peek(x -> l.add(7)).findFirst(); } catch (ConcurrentModificationException e) { return "CME"; } });
        t("modify in count", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2, 3); try { return l.stream().peek(x -> l.add(7)).count(); } catch (ConcurrentModificationException e) { return "CME"; } });
        t("modify via iterator of stream", () -> { List<Integer> l = of(new ArrayList<>(), 1, 2, 3); Iterator<Integer> it = l.stream().iterator(); it.next(); l.remove(0); try { return it.next(); } catch (ConcurrentModificationException e) { return "CME"; } });
        t("HashMap modify while streaming values", () -> { Map<Integer, Integer> m = new HashMap<>(); m.put(1, 1); m.put(2, 2); try { m.values().stream().forEach(v -> m.put(v + 10, 0)); return "done"; } catch (ConcurrentModificationException e) { return "CME"; } });

        // streams over arrays
        t("Arrays.stream", () -> Arrays.stream(new String[] {"a", "b", "c"}).collect(Collectors.joining()));
        t("Arrays.stream range", () -> Arrays.stream(new String[] {"a", "b", "c", "d"}, 1, 3).collect(Collectors.joining()));
        t("Arrays.stream range empty", () -> Arrays.stream(new String[] {"a"}, 1, 1).count());
        t("Arrays.stream int", () -> Arrays.stream(new int[] {4, 5, 6}).sum());
        t("Arrays.stream int range", () -> Arrays.stream(new int[] {4, 5, 6, 7}, 1, 3).sum());
        t("Arrays.stream long", () -> Arrays.stream(new long[] {4, 5, 6}).max());
        t("Arrays.stream long range", () -> Arrays.stream(new long[] {4, 5, 6, 7}, 2, 4).sum());
        t("Arrays.stream double", () -> Arrays.stream(new double[] {4, 5, 6}).average());
        t("Arrays.stream double range", () -> Arrays.stream(new double[] {4, 5, 6, 7}, 0, 2).sum());
        t("Arrays.stream subtype", () -> Arrays.<Object>stream(new String[] {"a"}).count());
        t("Arrays.stream with nulls", () -> Arrays.stream(new String[] {"a", null}).collect(Collectors.toList()));
        t("Arrays.stream 2d", () -> Arrays.stream(new int[][] {{1, 2}, {3}}).mapToInt(r -> r.length).sum());
        t("Arrays.stream 2d flat", () -> Arrays.stream(new int[][] {{1, 2}, {3}}).flatMapToInt(Arrays::stream).sum());
        t("Arrays.stream sorted copy", () -> { int[] a = {3, 1, 2}; int[] b = Arrays.stream(a).sorted().toArray(); return Arrays.toString(a) + Arrays.toString(b); });
        t("array to list round trip", () -> Arrays.asList(Arrays.stream(new Integer[] {1, 2}).toArray(Integer[]::new)));
        t("Stream.of array element type", () -> Stream.of("a", "b").toArray().getClass().getSimpleName());
        t("string array joined", () -> String.join("-", Stream.of("a", "b").toArray(String[]::new)));
        t("boxed array of ints", () -> Arrays.stream(new int[] {1, 2}).boxed().toArray(Integer[]::new).length);
        t("Arrays.setAll-like", () -> IntStream.range(0, 5).map(i -> i * i).toArray().length);
        t("Arrays.stream byte-free char", () -> "xyz".chars().mapToObj(c -> String.valueOf((char) c)).collect(Collectors.joining(",")));
    }
}
