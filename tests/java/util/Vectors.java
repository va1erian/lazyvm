package util;

import java.util.*;
import java.util.function.*;

/** Vector, Stack, Hashtable, Dictionary and Enumeration: legacy API, capacities and iteration order. */
public class Vectors {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static String drain(Enumeration<?> e) {
        StringBuilder sb = new StringBuilder();
        while (e.hasMoreElements()) sb.append(e.nextElement()).append(' ');
        return sb.toString();
    }

    public static void main(String[] args) {
        Vector<String> v = new Vector<>();
        System.out.println(v.capacity() + " " + v.size() + " " + v.isEmpty());
        for (int i = 0; i < 11; i++) v.addElement("e" + i);
        System.out.println(v.capacity() + " " + v.size() + " " + v + " " + v.firstElement() + " " + v.lastElement() + " " + v.elementAt(3));
        Vector<Integer> inc = new Vector<>(2, 3);
        for (int i = 0; i < 9; i++) { inc.add(i); System.out.print(inc.capacity() + " "); }
        System.out.println();
        Vector<Integer> doubling = new Vector<>(1);
        for (int i = 0; i < 9; i++) { doubling.add(i); System.out.print(doubling.capacity() + " "); }
        System.out.println();
        v.insertElementAt("ins", 2); v.setElementAt("set", 0); v.removeElementAt(5); v.add(1, "add"); v.remove("e9"); v.remove(0);
        System.out.println(v + " " + v.indexOf("e7") + " " + v.indexOf("e7", 8) + " " + v.lastIndexOf("e1") + " " + v.lastIndexOf("e1", 1) + " " + v.contains("ins") + " " + v.removeElement("zzz") + " " + v.removeElement("ins"));
        t("get past end", () -> v.get(99));
        t("get neg", () -> v.get(-1));
        t("elementAt past end", () -> v.elementAt(99));
        t("elementAt neg", () -> v.elementAt(-1));
        t("setElementAt past end", () -> { v.setElementAt("x", 99); return 0; });
        t("set past end", () -> v.set(99, "x"));
        t("remove past end", () -> v.remove(99));
        t("removeElementAt past end", () -> { v.removeElementAt(99); return 0; });
        t("removeElementAt neg", () -> { v.removeElementAt(-1); return 0; });
        t("insertElementAt past end", () -> { v.insertElementAt("x", 99); return 0; });
        t("add past end", () -> { v.add(99, "x"); return 0; });
        t("addAll@ bad", () -> v.addAll(99, Arrays.asList("x")));
        t("lastIndexOf bad", () -> v.lastIndexOf("e1", 99));
        t("first empty", () -> new Vector<String>().firstElement());
        t("last empty", () -> new Vector<String>().lastElement());
        t("ctor neg", () -> new Vector<String>(-1));
        t("ctor null", () -> new Vector<String>((Collection<String>) null));
        t("listIterator bad", () -> v.listIterator(99));
        t("subList", () -> { List<String> s = v.subList(1, 4); s.set(0, "sub"); return s + " " + v.get(1); });
        t("setSize", () -> { Vector<String> c = new Vector<>(Arrays.asList("a", "b", "c")); c.setSize(5); String s = c.toString(); c.setSize(1); return s + " " + c + " " + c.capacity(); });
        t("setSize neg", () -> { new Vector<String>().setSize(-1); return 0; });
        t("ensureCapacity/trimToSize", () -> { Vector<String> c = new Vector<>(Arrays.asList("a")); c.ensureCapacity(50); int a = c.capacity(); c.trimToSize(); return a + " " + c.capacity(); });
        t("copyInto", () -> { Object[] a = new Object[4]; new Vector<String>(Arrays.asList("x", "y")).copyInto(a); return Arrays.toString(a); });
        t("copyInto small", () -> { new Vector<String>(Arrays.asList("x", "y")).copyInto(new Object[1]); return 0; });
        t("removeAllElements/clone", () -> { Vector<String> c = new Vector<>(Arrays.asList("a", "b")); @SuppressWarnings("unchecked") Vector<String> d = (Vector<String>) c.clone(); c.removeAllElements(); d.add("c"); return c + " " + d + " " + c.equals(new Vector<String>()) + " " + d.hashCode(); });
        t("toArray", () -> Arrays.toString(v.toArray()) + Arrays.toString(v.toArray(new String[0])).length() + v.toArray(new String[20]).length);
        t("forEach/removeIf/replaceAll/sort", () -> { Vector<Integer> c = new Vector<>(Arrays.asList(5, 3, 8, 1)); c.sort(null); c.replaceAll(x -> x * 2); c.removeIf(x -> x > 10); StringBuilder sb = new StringBuilder(); c.forEach(sb::append); return c + " " + sb; });
        t("forEach CME", () -> { Vector<Integer> c = new Vector<>(Arrays.asList(1, 2, 3)); c.forEach(x -> c.add(1)); return c; });
        t("elements", () -> drain(new Vector<Integer>(Arrays.asList(1, 2, 3)).elements()));
        t("elements end", () -> new Vector<Integer>().elements().nextElement());
        t("equals", () -> new Vector<Integer>(Arrays.asList(1, 2)).equals(Arrays.asList(1, 2)) + " " + new Vector<Integer>(Arrays.asList(1, 2)).hashCode());
        Stack<Integer> st = new Stack<>();
        System.out.println(st.empty() + " " + st.push(1) + " " + st.push(2) + " " + st.push(3) + " " + st + " " + st.peek() + " " + st.search(3) + " " + st.search(1) + " " + st.search(9));
        System.out.println(st.pop() + " " + st.pop() + " " + st + " " + st.empty() + " " + st.size() + " " + st.firstElement());
        st.pop();
        t("pop empty", () -> st.pop());
        t("peek empty", () -> st.peek());
        t("stack list ops", () -> { st.push(5); st.add(6); st.add(0, 4); return st + " " + st.get(0) + " " + st.indexOf(6) + " " + st.search(4); });
        t("stack iteration order", () -> { Stack<String> s = new Stack<>(); s.push("a"); s.push("b"); s.push("c"); StringBuilder sb = new StringBuilder(); for (String x : s) sb.append(x); return sb + " " + s.pop() + s.pop(); });
        Hashtable<String, Integer> ht = new Hashtable<>();
        String[] ks = {"one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen"};
        for (int i = 0; i < ks.length; i++) { ht.put(ks[i], i + 1); if (i == 7 || i == 8 || i == 12) System.out.println(ht); }
        System.out.println(ht.size() + " " + ht.get("six") + " " + ht.get("none") + " " + ht.containsKey("ten") + " " + ht.contains(11) + " " + ht.containsValue(99) + " " + ht.put("six", 66) + " " + ht.remove("one") + " " + ht.remove("one"));
        System.out.println(drain(ht.keys()) + "| " + drain(ht.elements()));
        System.out.println(ht.keySet() + " " + ht.values() + " " + ht.entrySet());
        Hashtable<Integer, String> hi = new Hashtable<>();
        for (int i = 0; i < 30; i++) hi.put(i * 7, "v" + i);
        System.out.println(hi);
        Hashtable<Integer, String> small = new Hashtable<>(1);
        for (int i = 0; i < 12; i++) small.put(i, "s");
        System.out.println(small);
        Hashtable<Integer, String> lf = new Hashtable<>(5, 0.5f);
        for (int i = 0; i < 12; i++) lf.put(i * 3, "s");
        System.out.println(lf + " " + new Hashtable<Integer, String>(lf).equals(lf));
        t("null key", () -> ht.put(null, 1));
        t("null value", () -> ht.put("x", null));
        t("get null", () -> ht.get(null));
        t("contains null", () -> ht.contains(null));
        t("containsKey null", () -> ht.containsKey(null));
        t("remove null", () -> ht.remove(null));
        t("ctor bad capacity", () -> new Hashtable<String, String>(-1));
        t("ctor bad load", () -> new Hashtable<String, String>(1, 0f));
        t("ctor zero", () -> { Hashtable<Integer, String> z = new Hashtable<>(0); z.put(1, "a"); z.put(2, "b"); return z; });
        t("putAll/copy", () -> { Hashtable<Integer, String> c = new Hashtable<>(Collections.singletonMap(1, "x")); c.putAll(Collections.singletonMap(2, "y")); return c; });
        t("putAll null value", () -> new Hashtable<Integer, String>(Collections.singletonMap(1, (String) null)));
        t("clone", () -> { @SuppressWarnings("unchecked") Hashtable<String, Integer> c = (Hashtable<String, Integer>) ht.clone(); c.put("extra", 0); return c.size() + " " + ht.size() + " " + c.equals(ht); });
        t("keySet remove/retain", () -> { Hashtable<Integer, String> c = new Hashtable<>(hi); c.keySet().removeIf(k -> k % 2 == 0); c.values().remove("v1"); c.entrySet().remove(new AbstractMap.SimpleEntry<Integer, String>(21, "v3")); return c; });
        t("entry setValue", () -> { Hashtable<Integer, String> c = new Hashtable<>(Collections.singletonMap(1, "a")); Map.Entry<Integer, String> e = c.entrySet().iterator().next(); e.setValue("b"); return c + " " + e + " " + e.equals(new AbstractMap.SimpleEntry<Integer, String>(1, "b")); });
        t("entry setValue null", () -> { Hashtable<Integer, String> c = new Hashtable<>(Collections.singletonMap(1, "a")); c.entrySet().iterator().next().setValue(null); return 0; });
        t("hashCode/equals", () -> { Hashtable<Integer, Integer> a = new Hashtable<>(); a.put(1, 2); a.put(3, 4); Map<Integer, Integer> h = new HashMap<>(a); return a.equals(h) + " " + h.equals(a) + " " + (a.hashCode() == h.hashCode()); });
        t("self reference", () -> { Hashtable<String, Object> c = new Hashtable<>(); c.put("me", c); return c + " " + c.hashCode(); });
        t("toString empty", () -> new Hashtable<String, String>() + " " + new Hashtable<String, String>().keys().hasMoreElements() + " " + new Hashtable<String, String>().keySet().iterator().hasNext());
        t("capacity rules", () -> {
            StringBuilder sb = new StringBuilder();
            Vector<Integer> c = new Vector<>(Arrays.asList(1, 2, 3)); sb.append(c.capacity()); c.add(4); sb.append(' ').append(c.capacity());
            Vector<Integer> big = new Vector<>(20); big.add(1); big.add(2); @SuppressWarnings("unchecked") Vector<Integer> cl = (Vector<Integer>) big.clone(); sb.append(" clone ").append(cl.capacity());
            Vector<Integer> d = new Vector<>(4); d.addAll(Collections.nCopies(9, 0)); sb.append(" addAll9 ").append(d.capacity());
            Vector<Integer> e = new Vector<>(4, 3); e.addAll(Collections.nCopies(2, 0)); e.addAll(Collections.nCopies(4, 0)); sb.append(" inc3 ").append(e.capacity()); e.addAll(0, Collections.nCopies(5, 0)); sb.append(' ').append(e.capacity());
            Vector<Integer> f = new Vector<>(4, -2); for (int i = 0; i < 5; i++) f.add(i); sb.append(" negInc ").append(f.capacity());
            Vector<Integer> g = new Vector<>(4); g.ensureCapacity(5); sb.append(" ensure5 ").append(g.capacity()); g.ensureCapacity(0); g.ensureCapacity(-1); sb.append(' ').append(g.capacity()); g.ensureCapacity(17); sb.append(' ').append(g.capacity());
            Vector<Integer> h = new Vector<>(); h.setSize(30); sb.append(" setSize30 ").append(h.capacity()); h.setSize(31); sb.append(' ').append(h.capacity());
            Vector<Integer> z = new Vector<>(); z.trimToSize(); sb.append(" trim ").append(z.capacity()); z.add(1); sb.append(' ').append(z.capacity()); z.insertElementAt(0, 0); sb.append(' ').append(z.capacity());
            Vector<Integer> zero = new Vector<>(0, 0); zero.addElement(1); sb.append(" zero ").append(zero.capacity());
            return sb + " stack " + new Stack<Integer>().capacity();
        });
        t("Dictionary", () -> { Dictionary<String, Integer> d = new Hashtable<>(); d.put("a", 1); d.put("b", 2); return d.size() + " " + d.get("a") + " " + d.isEmpty() + " " + drain(d.keys()) + d.remove("a") + d.remove("zz") + d.size(); });
        System.out.println("done");
    }
}
