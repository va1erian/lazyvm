package util;

import java.util.*;
import java.util.function.*;

/**
 * Which calls Collections makes on a list that is not RandomAccess (get/set, or iterators) at the
 * sizes where it changes its approach; a RandomAccess list never leaves get/set.
 */
public class CollectionsCalls {
    static StringBuilder log = new StringBuilder();

    static class Plain<E> extends AbstractList<E> {
        final ArrayList<E> d;
        Plain(Collection<E> c) { d = new ArrayList<>(c); }
        public E get(int i) { log.append('g'); return d.get(i); }
        public E set(int i, E e) { log.append('s'); return d.set(i, e); }
        public int size() { return d.size(); }
        public ListIterator<E> listIterator(int i) { log.append('I'); return super.listIterator(i); }
        public Iterator<E> iterator() { log.append('T'); return super.iterator(); }
    }

    static class Fast<E> extends Plain<E> implements RandomAccess {
        Fast(Collection<E> c) { super(c); }
    }

    static String counts() {
        int g = 0, s = 0, li = 0, it = 0;
        for (char c : log.toString().toCharArray()) {
            if (c == 'g') g++; else if (c == 's') s++; else if (c == 'I') li++; else it++;
        }
        return "get " + g + " set " + s + " listIterator " + li + " iterator " + it;
    }

    static List<Integer> ints(int n) {
        List<Integer> b = new ArrayList<>();
        for (int i = 0; i < n; i++) b.add(i);
        return b;
    }

    static void run(String name, int[] sizes, Consumer<List<Integer>> op) {
        for (int n : sizes) {
            for (int kind = 0; kind < 2; kind++) {
                Plain<Integer> l = kind == 0 ? new Plain<>(ints(n)) : new Fast<>(ints(n));
                log.setLength(0);
                op.accept(l);
                System.out.println(name + " " + n + (kind == 0 ? " plain: " : " fast: ") + counts() + (n < 30 ? " " + l.d : ""));
            }
        }
    }

    public static void main(String[] args) {
        run("reverse", new int[]{17, 18}, Collections::reverse);
        run("shuffle", new int[]{4, 5}, l -> Collections.shuffle(l, new Random(3)));
        run("fill", new int[]{24, 25}, l -> Collections.fill(l, 7));
        run("rotate", new int[]{99, 100}, l -> Collections.rotate(l, 3));
        run("replaceAll", new int[]{10, 11}, l -> Collections.replaceAll(l, 3, 9));
        run("copy", new int[]{9, 10}, l -> Collections.copy(l, new LinkedList<>(((Plain<Integer>) l).d)));
        run("binarySearch", new int[]{4999, 5000}, l -> System.out.print(Collections.binarySearch(l, 1234) + " "));
        run("binarySearch cmp", new int[]{4999, 5000}, l -> System.out.print(Collections.binarySearch(l, 1234, Comparator.<Integer>naturalOrder()) + " "));
        run("indexOfSubList", new int[]{20, 34, 35, 36, 60}, l -> System.out.print(Collections.indexOfSubList(l, Arrays.asList(8, 9)) + " "));
        run("indexOfSubList linked target", new int[]{34, 35, 60}, l -> System.out.print(Collections.indexOfSubList(l, new LinkedList<>(Arrays.asList(8, 9))) + " "));
        run("indexOfSubList no match", new int[]{34, 40}, l -> System.out.print(Collections.indexOfSubList(l, Arrays.asList(9, 8)) + " "));
        run("indexOfSubList partial", new int[]{40}, l -> System.out.print(Collections.indexOfSubList(l, Arrays.asList(8, 9, 11)) + " "));
        run("lastIndexOfSubList", new int[]{20, 34, 35, 36, 60}, l -> System.out.print(Collections.lastIndexOfSubList(l, Arrays.asList(8, 9)) + " "));
        run("lastIndexOfSubList linked target", new int[]{35, 60}, l -> System.out.print(Collections.lastIndexOfSubList(l, new LinkedList<>(Arrays.asList(8, 9))) + " "));
        run("lastIndexOfSubList partial", new int[]{40}, l -> System.out.print(Collections.lastIndexOfSubList(l, Arrays.asList(8, 9, 11)) + " "));
        run("lastIndexOfSubList at start", new int[]{40}, l -> System.out.print(Collections.lastIndexOfSubList(l, Arrays.asList(0, 1)) + " "));
        run("lastIndexOfSubList longer", new int[]{40}, l -> System.out.print(Collections.lastIndexOfSubList(l, ints(50)) + " "));
        run("sublist empty target", new int[]{40}, l -> System.out.print(Collections.indexOfSubList(l, Arrays.<Integer>asList()) + " " + Collections.lastIndexOfSubList(l, Arrays.<Integer>asList()) + " "));
        run("swap", new int[]{3}, l -> Collections.swap(l, 0, 2));
        run("sort", new int[]{5}, Collections::sort);
        run("max", new int[]{5}, Collections::max);
        System.out.println("done");
    }
}
