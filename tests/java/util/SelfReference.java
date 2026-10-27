package util;

import java.util.*;
import java.util.function.*;

/** toString and hashCode of collections that contain themselves, directly or through a view. */
public class SelfReference {
    static String t(Supplier<Object> s) {
        try {
            return String.valueOf(s.get());
        } catch (StackOverflowError e) {
            return "StackOverflowError";
        }
    }

    static void show(String name, Object c) {
        System.out.println(name + ": " + t(c::toString) + " hash " + t(c::hashCode));
    }

    enum St { NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED }

    public static void main(String[] args) {
        List<Object> al = new ArrayList<>(); al.add(al); al.add(1); show("ArrayList", al);
        List<Object> ll = new LinkedList<>(); ll.add(ll); show("LinkedList", ll);
        Vector<Object> v = new Vector<>(); v.add(v); show("Vector", v);
        Stack<Object> st = new Stack<>(); st.push(st); show("Stack", st);
        ArrayDeque<Object> ad = new ArrayDeque<>(); ad.add(ad); ad.add(2); System.out.println("ArrayDeque: " + ad);
        Set<Object> hs = new HashSet<>(); hs.add("a"); hs.add(hs); show("HashSet", hs);
        Set<Object> lhs = new LinkedHashSet<>(); lhs.add(lhs); show("LinkedHashSet", lhs);
        Map<Object, Object> hm = new HashMap<>(); hm.put("k", hm); show("HashMap value", hm);
        Map<Object, Object> lhm = new LinkedHashMap<>(); lhm.put(lhm, lhm); show("LinkedHashMap key and value", lhm);
        Map<String, Object> tm = new TreeMap<>(); tm.put("k", tm); show("TreeMap value", tm);
        Hashtable<Object, Object> ht = new Hashtable<>(); ht.put("k", ht); show("Hashtable value", ht);
        Map<Object, Object> ih = new IdentityHashMap<>(); ih.put(ih, ih); show("IdentityHashMap key and value", ih);
        EnumMap<St, Object> em = new EnumMap<>(St.class); em.put(St.NEW, em); show("EnumMap value", em);
        List<Object> base = new ArrayList<>(); List<Object> u = Collections.unmodifiableList(base); base.add(u); show("list holding its unmodifiable view", u);
        List<Object> base2 = new ArrayList<>(); List<Object> s = Collections.synchronizedList(base2); base2.add(s); show("list holding its synchronized view", s);
        List<Object> base3 = new ArrayList<>(); base3.add(0); base3.add(base3); show("subList over the list", base3.subList(0, 2));
        Map<Object, Object> base4 = new HashMap<>(); Map<Object, Object> um = Collections.unmodifiableMap(base4); base4.put("k", um); show("map holding its unmodifiable view", um);
        Collection<Object> vals = hm.values(); System.out.println("values view: " + t(vals::toString) + " keySet " + hm.keySet() + " entrySet " + t(() -> hm.entrySet().toString()));
        Object[] arr = new Object[2]; arr[0] = arr; arr[1] = new Object[]{arr};
        System.out.println("Arrays.deepToString: " + Arrays.deepToString(arr) + " deepHashCode " + t(() -> Arrays.deepHashCode(arr)) + " asList " + t(() -> Arrays.asList(arr).toString().startsWith("[[Ljava.lang.Object;@")));
        List<Object> outer = new ArrayList<>(), inner = new ArrayList<>(); outer.add(inner); inner.add(outer);
        System.out.println("two lists holding each other: " + t(outer::toString) + " / " + t(outer::hashCode));
        Hashtable<String, Object> a = new Hashtable<>(), b = new Hashtable<>(); a.put("k", "v"); a.put("b", b); b.put("a", a);
        System.out.println("two Hashtables holding each other: hash " + a.hashCode() + " " + b.hashCode() + " toString " + t(a::toString));
        System.out.println("done");
    }
}
