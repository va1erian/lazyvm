package concurrent;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/** CopyOnWriteArrayList and CopyOnWriteArraySet: snapshots, index errors, unsupported iterator writes. */
public class CopyOnWrites {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    static CopyOnWriteArrayList<String> abc() { return new CopyOnWriteArrayList<>(Arrays.asList("a", "b", "c")); }

    static void listBasics() {
        CopyOnWriteArrayList<String> l = abc();
        show("toString", () -> l);
        show("empty", () -> new CopyOnWriteArrayList<String>());
        show("from array", () -> new CopyOnWriteArrayList<>(new String[] {"x", null}));
        show("null collection", () -> new CopyOnWriteArrayList<String>((Collection<String>) null));
        show("null array", () -> new CopyOnWriteArrayList<String>((String[]) null));
        show("array copied", () -> {
            String[] src = {"x"};
            CopyOnWriteArrayList<String> c = new CopyOnWriteArrayList<>(src);
            src[0] = "y";
            return c;
        });
        show("add", () -> l.add("d"));
        show("add null", () -> l.add(null));
        show("contains null", () -> l.contains(null));
        show("indexOf null", () -> l.indexOf(null));
        show("remove null", () -> l.remove(null));
        show("get", () -> l.get(1));
        show("get neg", () -> l.get(-1));
        show("get far", () -> l.get(99));
        show("set", () -> l.set(0, "A"));
        show("set far", () -> l.set(99, "x"));
        show("add at", () -> { l.add(1, "m"); return l; });
        show("add at far", () -> { l.add(99, "x"); return "no"; });
        show("add at neg", () -> { l.add(-1, "x"); return "no"; });
        show("remove at", () -> l.remove(1));
        show("remove at far", () -> l.remove(99));
        show("remove at neg", () -> l.remove(-1));
        show("remove obj", () -> l.remove("d"));
        show("remove absent", () -> l.remove("zz"));
        show("addAll", () -> l.addAll(Arrays.asList("x", "y")));
        show("addAll empty", () -> l.addAll(new ArrayList<>()));
        show("addAll at", () -> l.addAll(1, Arrays.asList("p")));
        show("addAll at far", () -> l.addAll(99, Arrays.asList("p")));
        show("addAll at empty", () -> l.addAll(0, new ArrayList<>()));
        show("addAll null", () -> l.addAll(null));
        show("state", () -> l);
        show("indexOf from", () -> abc().indexOf("c", 1));
        show("indexOf from past", () -> abc().indexOf("a", 1));
        show("indexOf far", () -> abc().indexOf("a", 99));
        show("indexOf neg", () -> abc().indexOf("a", -1));
        show("lastIndexOf from", () -> abc().lastIndexOf("a", 2));
        show("lastIndexOf from 0", () -> abc().lastIndexOf("c", 0));
        show("lastIndexOf far", () -> abc().lastIndexOf("a", 99));
        show("lastIndexOf neg", () -> abc().lastIndexOf("a", -1));
        show("lastIndexOf", () -> new CopyOnWriteArrayList<>(Arrays.asList("a", null, "a", null)).lastIndexOf("a"));
        show("lastIndexOf null", () -> new CopyOnWriteArrayList<>(Arrays.asList("a", null, "a", null)).lastIndexOf(null));
        show("lastIndexOf absent", () -> abc().lastIndexOf("z"));
        show("addIfAbsent new", () -> l.addIfAbsent("new"));
        show("addIfAbsent old", () -> l.addIfAbsent("new"));
        show("addAllAbsent", () -> l.addAllAbsent(Arrays.asList("new", "n2", "n2", "n3")));
        show("addAllAbsent null", () -> l.addAllAbsent(null));
        show("after absent", () -> l);
        show("removeAll", () -> l.removeAll(Arrays.asList("A", "zz")));
        show("removeAll null", () -> l.removeAll(null));
        show("retainAll", () -> l.retainAll(Arrays.asList("new", "n2", "n3")));
        show("retainAll null", () -> l.retainAll(null));
        show("containsAll", () -> l.containsAll(Arrays.asList("new", "n2")));
        show("removeIf", () -> l.removeIf(s -> s.equals("n3")));
        show("removeIf none", () -> l.removeIf(s -> false));
        show("removeIf null", () -> l.removeIf(null));
        show("replaceAll", () -> { l.replaceAll(s -> s + "!"); return l; });
        show("replaceAll null", () -> { l.replaceAll(null); return "no"; });
        show("sort", () -> { l.sort(Comparator.reverseOrder()); return l; });
        show("sort null natural", () -> { l.sort(null); return l; });
        List<String> seen = new ArrayList<>();
        l.forEach(seen::add);
        show("forEach", () -> seen);
        show("forEach null", () -> { l.forEach(null); return "no"; });
        show("clone independent", () -> {
            @SuppressWarnings("unchecked") CopyOnWriteArrayList<String> c = (CopyOnWriteArrayList<String>) l.clone();
            c.add("more");
            return l.size() + " " + c.size();
        });
        show("equals list", () -> abc().equals(Arrays.asList("a", "b", "c")));
        show("list equals", () -> Arrays.asList("a", "b", "c").equals(abc()));
        show("equals other", () -> abc().equals("x"));
        show("hashCode", () -> abc().hashCode() == Arrays.asList("a", "b", "c").hashCode());
        show("toArray", () -> Arrays.toString(abc().toArray()));
        show("toArray small", () -> Arrays.toString(abc().toArray(new String[0])));
        show("toArray big", () -> Arrays.toString(abc().toArray(new String[5])));
        show("toArray bad type", () -> { try { return abc().toArray(new Integer[0]); } catch (ArrayStoreException e) { return "ArrayStoreException"; } });
        l.clear();
        show("clear", l::isEmpty);
    }

    static void iterators() {
        CopyOnWriteArrayList<String> l = abc();
        Iterator<String> it = l.iterator();
        l.add("later");
        l.remove("a");
        List<String> seen = new ArrayList<>();
        it.forEachRemaining(seen::add);
        show("snapshot", () -> seen);
        show("exhausted", it::hasNext);
        show("next past end", it::next);
        Iterator<String> rm = abc().iterator();
        rm.next();
        show("iterator remove", () -> { rm.remove(); return "no"; });
        ListIterator<String> li = abc().listIterator();
        show("hasPrevious", li::hasPrevious);
        show("previous at start", li::previous);
        show("next", li::next);
        show("nextIndex", li::nextIndex);
        show("previousIndex", li::previousIndex);
        show("previous", li::previous);
        show("li set", () -> { li.set("x"); return "no"; });
        show("li add", () -> { li.add("x"); return "no"; });
        show("li remove", () -> { li.remove(); return "no"; });
        show("listIterator at", () -> abc().listIterator(2).next());
        show("listIterator end", () -> abc().listIterator(3).hasNext());
        show("listIterator far", () -> abc().listIterator(4));
        show("listIterator neg", () -> abc().listIterator(-1));
        show("mutate during foreach", () -> {
            CopyOnWriteArrayList<String> m = abc();
            int count = 0;
            for (String s : m) { m.add(s + s); count++; }
            return count + " " + m;
        });
    }

    static void subLists() {
        CopyOnWriteArrayList<String> l = new CopyOnWriteArrayList<>(Arrays.asList("a", "b", "c", "d", "e"));
        List<String> s = l.subList(1, 4);
        show("sub", () -> s);
        show("sub size", s::size);
        show("sub get", () -> s.get(1));
        show("sub get far", () -> s.get(3));
        show("sub get neg", () -> s.get(-1));
        show("sub set", () -> s.set(0, "B"));
        show("sub set far", () -> s.set(3, "B"));
        show("through to parent", () -> l);
        show("sub add", () -> s.add("n"));
        show("sub add at", () -> { s.add(0, "first"); return s; });
        show("sub add far", () -> { s.add(99, "x"); return "no"; });
        show("sub remove at", () -> s.remove(0));
        show("sub remove far", () -> s.remove(99));
        show("sub remove obj", () -> s.remove("n"));
        show("sub remove absent", () -> s.remove("zz"));
        show("state", () -> s + " " + l);
        Iterator<String> it = s.iterator();
        show("sub iterator next", it::next);
        show("sub iterator remove", () -> { it.remove(); return "no"; });
        ListIterator<String> li = s.listIterator(1);
        show("sub listIterator", li::next);
        show("sub listIterator set", () -> { li.set("x"); return "no"; });
        show("sub listIterator far", () -> s.listIterator(99));
        show("nested", () -> s.subList(1, 3));
        show("nested bad", () -> s.subList(2, 1));
        show("bad from", () -> l.subList(-1, 1));
        show("bad to", () -> l.subList(0, 99));
        show("bad order", () -> l.subList(3, 2));
        show("empty sub", () -> l.subList(2, 2));
        l.add("outside");
        show("sub after outer change", s::size);
        show("sub get after outer change", () -> s.get(0));
        show("sub toString after outer change", () -> s.toString());
        show("sub iterator after outer change", s::iterator);
        CopyOnWriteArrayList<String> m = abc();
        List<String> ms = m.subList(0, 2);
        ms.clear();
        show("sub clear", () -> m);
        show("sub valid after own change", () -> ms.isEmpty());
        ms.add("z");
        show("sub after clear add", () -> m);
    }

    static void sets() {
        CopyOnWriteArraySet<String> s = new CopyOnWriteArraySet<>();
        show("empty", () -> s);
        show("add", () -> s.add("b"));
        show("add dup", () -> s.add("b"));
        show("add other", () -> s.add("a"));
        show("add null", () -> s.add(null));
        show("add null dup", () -> s.add(null));
        show("insertion order", () -> s);
        show("contains null", () -> s.contains(null));
        show("remove null", () -> s.remove(null));
        show("addAll", () -> s.addAll(Arrays.asList("a", "z", "z")));
        show("from collection order", () -> new CopyOnWriteArraySet<>(Arrays.asList("q", "p", "q", "r")));
        show("null collection", () -> new CopyOnWriteArraySet<String>(null));
        show("size", s::size);
        Iterator<String> it = s.iterator();
        s.add("later");
        s.remove("b");
        List<String> seen = new ArrayList<>();
        while (it.hasNext()) seen.add(it.next());
        show("snapshot", () -> seen);
        show("iterator next end", it::next);
        Iterator<String> rm = s.iterator();
        rm.next();
        show("iterator remove", () -> { rm.remove(); return "no"; });
        show("equals set", () -> s.equals(new HashSet<>(s)));
        show("set equals", () -> new HashSet<>(s).equals(s));
        show("equals order free", () -> new CopyOnWriteArraySet<>(Arrays.asList("x", "y")).equals(new CopyOnWriteArraySet<>(Arrays.asList("y", "x"))));
        show("equals list", () -> new CopyOnWriteArraySet<>(Arrays.asList("x")).equals(Arrays.asList("x")));
        show("hashCode", () -> s.hashCode() == new HashSet<>(s).hashCode());
        show("containsAll", () -> s.containsAll(Arrays.asList("a", "z")));
        show("containsAll empty", () -> s.containsAll(new ArrayList<>()));
        show("containsAll null", () -> s.containsAll(null));
        show("removeAll", () -> s.removeAll(Arrays.asList("a", "zz")));
        show("retainAll", () -> s.retainAll(Arrays.asList("z")));
        show("removeAll null", () -> s.removeAll(null));
        show("state", () -> s);
        show("removeIf", () -> s.removeIf(x -> x.equals("z")));
        show("removeIf null", () -> s.removeIf(null));
        List<String> fe = new ArrayList<>();
        new CopyOnWriteArraySet<>(Arrays.asList("m", "k")).forEach(fe::add);
        show("forEach", () -> fe);
        show("toArray", () -> Arrays.toString(new CopyOnWriteArraySet<>(Arrays.asList("m", "k")).toArray()));
        show("toArray typed", () -> Arrays.toString(new CopyOnWriteArraySet<>(Arrays.asList("m", "k")).toArray(new String[3])));
        s.clear();
        show("clear", s::isEmpty);
    }

    public static void main(String[] args) {
        listBasics();
        iterators();
        subLists();
        sets();
    }
}
