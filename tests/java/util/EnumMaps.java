package util;

import java.util.*;
import java.util.function.*;

/** EnumMap and EnumSet: ordinal order, null handling, factory methods, type checks. */
public class EnumMaps {
    enum Day { MON, TUE, WED, THU, FRI, SAT, SUN }

    enum Level {
        LOW { int w() { return 1; } },
        HIGH { int w() { return 2; } };
        abstract int w();
    }

    enum E64 { A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, A23, A24, A25, A26, A27, A28, A29, A30, A31,
        A32, A33, A34, A35, A36, A37, A38, A39, A40, A41, A42, A43, A44, A45, A46, A47, A48, A49, A50, A51, A52, A53, A54, A55, A56, A57, A58, A59, A60, A61, A62, A63 }

    enum E65 { A0, A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, A23, A24, A25, A26, A27, A28, A29, A30, A31,
        A32, A33, A34, A35, A36, A37, A38, A39, A40, A41, A42, A43, A44, A45, A46, A47, A48, A49, A50, A51, A52, A53, A54, A55, A56, A57, A58, A59, A60, A61, A62, A63, A64 }

    enum None { }

    enum Wide { W0, W1, W2, W3, W4, W5, W6, W7, W8, W9, W10, W11, W12, W13, W14, W15, W16, W17, W18, W19, W20, W21, W22, W23, W24,
        W25, W26, W27, W28, W29, W30, W31, W32, W33, W34, W35, W36, W37, W38, W39, W40, W41, W42, W43, W44, W45, W46, W47, W48,
        W49, W50, W51, W52, W53, W54, W55, W56, W57, W58, W59, W60, W61, W62, W63, W64, W65, W66, W67, W68, W69 }

    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    public static void main(String[] args) {
        EnumMap<Day, String> m = new EnumMap<>(Day.class);
        m.put(Day.FRI, "fri"); m.put(Day.MON, "mon"); m.put(Day.WED, null); m.put(Day.SUN, "sun");
        System.out.println(m + " " + m.size() + " " + m.keySet() + " " + m.values() + " " + m.entrySet());
        System.out.println(m.get(Day.MON) + " " + m.get(Day.TUE) + " " + m.get(Day.WED) + " " + m.containsKey(Day.WED) + " " + m.containsKey(Day.TUE) + " " + m.containsValue(null) + " " + m.containsValue("sun") + " " + m.containsValue("zzz"));
        System.out.println(m.put(Day.MON, "MON") + " " + m.put(Day.TUE, "tue") + " " + m.remove(Day.FRI) + " " + m.remove(Day.FRI) + " " + m);
        t("get non-enum", () -> m.get("MON") + " " + m.get(null) + " " + m.containsKey(Level.LOW) + " " + m.remove("x") + " " + m.containsKey(null));
        t("put null key", () -> m.put(null, "x"));
        t("put wrong enum", () -> ((Map) m).put(Level.LOW, "x"));
        t("iterator remove", () -> { Iterator<Day> it = m.keySet().iterator(); while (it.hasNext()) if (it.next().ordinal() % 2 == 0) it.remove(); return m; });
        t("entry setValue", () -> { for (Map.Entry<Day, String> e : m.entrySet()) e.setValue(e.getValue() + "!"); return m; });
        t("entry toString/equals", () -> { Map.Entry<Day, String> e = m.entrySet().iterator().next(); return e + " " + e.getKey() + " " + e.getValue() + " " + e.equals(new AbstractMap.SimpleEntry<Day, String>(Day.TUE, "tue!")); });
        t("entry removed", () -> { Iterator<Map.Entry<Day, String>> it = m.entrySet().iterator(); Map.Entry<Day, String> e = it.next(); it.remove(); return e.getKey(); });
        t("modify during iteration", () -> { EnumMap<Day, Integer> c = new EnumMap<>(Day.class); c.put(Day.MON, 1); c.put(Day.TUE, 2); StringBuilder sb = new StringBuilder(); for (Day d : c.keySet()) { sb.append(d); c.put(Day.SUN, 7); } return sb + " " + c; });
        t("copy ctors", () -> { EnumMap<Day, Integer> a = new EnumMap<>(Day.class); a.put(Day.SAT, 1); EnumMap<Day, Integer> b = new EnumMap<>(a); Map<Day, Integer> h = new HashMap<>(); h.put(Day.THU, 4); return b + " " + new EnumMap<Day, Integer>(h) + " " + new EnumMap<Day, Integer>((Map<Day, Integer>) a); });
        t("copy empty map", () -> new EnumMap<Day, Integer>(new HashMap<Day, Integer>()));
        t("not an enum", () -> new EnumMap(String.class));
        t("putAll", () -> { EnumMap<Day, Integer> a = new EnumMap<>(Day.class); a.putAll(Collections.singletonMap(Day.SUN, 1)); a.putAll(new EnumMap<Day, Integer>(Day.class)); EnumMap<Level, Integer> o = new EnumMap<>(Level.class); o.put(Level.LOW, 1); return a + " " + a.size(); });
        t("putAll wrong type", () -> { EnumMap<Day, Integer> a = new EnumMap<>(Day.class); EnumMap<Level, Integer> o = new EnumMap<>(Level.class); o.put(Level.LOW, 1); a.putAll((Map) o); return a; });
        t("equals/clone", () -> { EnumMap<Day, Integer> a = new EnumMap<>(Day.class); a.put(Day.MON, 1); EnumMap<Day, Integer> c = a.clone(); c.put(Day.TUE, 2); return a.equals(c) + " " + a.equals(Collections.singletonMap(Day.MON, 1)) + " " + a + " " + c + " " + new EnumMap<Day, Integer>(Day.class).equals(new EnumMap<Level, Integer>(Level.class)); });
        t("constant bodies", () -> { EnumMap<Level, Integer> a = new EnumMap<>(Level.class); a.put(Level.HIGH, Level.HIGH.w()); a.put(Level.LOW, Level.LOW.w()); return a + " " + Level.LOW.getClass().equals(Level.class) + " " + Level.LOW.getDeclaringClass().getSimpleName(); });
        t("clear/values remove", () -> { EnumMap<Day, String> a = new EnumMap<>(Day.class); a.put(Day.MON, "x"); a.put(Day.TUE, "y"); a.values().remove("x"); String s = a.toString(); a.keySet().remove(Day.TUE); return s + " " + a + " " + a.isEmpty(); });
        EnumSet<Day> s = EnumSet.of(Day.SAT, Day.MON, Day.WED);
        System.out.println(s + " " + s.size() + " " + s.contains(Day.MON) + " " + s.contains(Day.TUE) + " " + s.contains(null) + " " + s.contains("MON") + " " + s.contains(Level.LOW));
        System.out.println(EnumSet.allOf(Day.class) + " " + EnumSet.noneOf(Day.class) + " " + EnumSet.complementOf(s) + " " + EnumSet.range(Day.TUE, Day.FRI) + " " + EnumSet.range(Day.TUE, Day.TUE) + " " + EnumSet.copyOf(s) + " " + EnumSet.copyOf(Arrays.asList(Day.SUN, Day.TUE)));
        System.out.println(EnumSet.of(Day.MON, Day.TUE) + " " + EnumSet.of(Day.MON, Day.TUE, Day.WED) + " " + EnumSet.of(Day.MON, Day.TUE, Day.WED, Day.THU) + " " + EnumSet.of(Day.MON, Day.TUE, Day.WED, Day.THU, Day.FRI) + " " + EnumSet.of(Day.SUN, Day.MON, Day.TUE, Day.WED, Day.THU, Day.FRI));
        t("range bad", () -> EnumSet.range(Day.FRI, Day.TUE));
        t("copyOf empty", () -> EnumSet.copyOf(new ArrayList<Day>()));
        t("copyOf empty EnumSet", () -> EnumSet.copyOf(EnumSet.noneOf(Day.class)));
        t("of null", () -> EnumSet.of((Day) null));
        t("add null", () -> s.add(null));
        t("add/remove", () -> s.add(Day.MON) + " " + s.add(Day.FRI) + " " + s.remove(Day.WED) + " " + s.remove(Day.WED) + " " + s.remove("x") + " " + s);
        t("bulk", () -> { EnumSet<Day> a = EnumSet.of(Day.MON, Day.TUE, Day.WED); return a.containsAll(EnumSet.of(Day.MON)) + " " + a.addAll(EnumSet.of(Day.SUN)) + " " + a.addAll(EnumSet.of(Day.SUN)) + " " + a.removeAll(EnumSet.of(Day.TUE, Day.THU)) + " " + a.retainAll(EnumSet.of(Day.MON, Day.SUN, Day.SAT)) + " " + a + " " + a.retainAll(EnumSet.noneOf(Day.class)) + " " + a; });
        t("bulk other collections", () -> { EnumSet<Day> a = EnumSet.noneOf(Day.class); a.addAll(Arrays.asList(Day.SUN, Day.MON)); a.removeAll(Arrays.asList(Day.SUN)); a.retainAll(new HashSet<Day>(Arrays.asList(Day.MON))); return a + " " + a.containsAll(Arrays.asList(Day.MON)); });
        t("mixed types", () -> { EnumSet<Day> a = EnumSet.of(Day.MON); EnumSet<Level> b = EnumSet.of(Level.LOW); return a.equals(b) + " " + a.containsAll(b) + " " + a.removeAll(b) + " " + a.equals(EnumSet.noneOf(Level.class)); });
        t("addAll wrong type", () -> { EnumSet<Day> a = EnumSet.of(Day.MON); ((EnumSet) a).addAll(EnumSet.of(Level.LOW)); return a; });
        t("iterator", () -> { EnumSet<Day> a = EnumSet.allOf(Day.class); Iterator<Day> it = a.iterator(); StringBuilder sb = new StringBuilder(); while (it.hasNext()) { Day d = it.next(); sb.append(d.ordinal()); if (d.ordinal() % 2 == 1) it.remove(); } return sb + " " + a; });
        t("iterator snapshot", () -> { EnumSet<Day> a = EnumSet.of(Day.MON, Day.WED); Iterator<Day> it = a.iterator(); a.add(Day.TUE); a.remove(Day.WED); StringBuilder sb = new StringBuilder(); while (it.hasNext()) sb.append(it.next()); return sb + " " + a; });
        t("wide iterator", () -> { EnumSet<Wide> a = EnumSet.of(Wide.W0, Wide.W5, Wide.W64, Wide.W66); Iterator<Wide> it = a.iterator(); StringBuilder sb = new StringBuilder(); sb.append(it.next()); a.add(Wide.W1); a.add(Wide.W6); a.add(Wide.W65); a.remove(Wide.W66); while (it.hasNext()) sb.append(it.next()); return sb + " " + a; });
        t("iterator errors", () -> { Iterator<Day> it = EnumSet.noneOf(Day.class).iterator(); return it.hasNext(); });
        t("iterator next empty", () -> EnumSet.noneOf(Day.class).iterator().next());
        t("iterator remove first", () -> { EnumSet.of(Day.MON).iterator().remove(); return 0; });
        t("equals/hash/clone", () -> { EnumSet<Day> a = EnumSet.of(Day.MON, Day.TUE); EnumSet<Day> c = a.clone(); c.add(Day.WED); return a.equals(EnumSet.of(Day.TUE, Day.MON)) + " " + a.equals(new HashSet<Day>(a)) + " " + a.equals(c) + " " + a + c + " " + a.isEmpty() + c.size(); });
        t("not an enum", () -> EnumSet.noneOf((Class) String.class));
        t("wide", () -> { EnumSet<Wide> w = EnumSet.range(Wide.W3, Wide.W66); w.remove(Wide.W64); w.add(Wide.W0); EnumSet<Wide> c = EnumSet.complementOf(w); return w.size() + " " + c + " " + w.contains(Wide.W65) + " " + w.contains(Wide.W67) + " " + EnumSet.allOf(Wide.class).size() + " " + EnumSet.of(Wide.W69, Wide.W1, Wide.W64) + " " + w.getClass().getSimpleName() + " " + EnumSet.of(Day.MON).getClass().getSimpleName() + " " + c.clone().getClass().getSimpleName(); });
        t("wide map", () -> { EnumMap<Wide, Integer> w = new EnumMap<>(Wide.class); w.put(Wide.W69, 1); w.put(Wide.W0, 2); w.put(Wide.W64, 3); return w + " " + w.keySet(); });
        System.out.println("set classes: 64 " + EnumSet.allOf(E64.class).getClass().getName() + ", 65 " + EnumSet.noneOf(E65.class).getClass().getName()
            + ", 0 " + EnumSet.allOf(None.class).getClass().getName() + " " + EnumSet.allOf(None.class) + ", 65 range " + EnumSet.range(E65.A62, E65.A64) + " " + EnumSet.complementOf(EnumSet.range(E65.A1, E65.A63)));
        System.out.println("done");
    }
}
