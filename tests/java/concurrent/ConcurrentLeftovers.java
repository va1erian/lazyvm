package concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Members no other test reaches: LinkedBlockingDeque's timed offer, KeySetView.forEach. */
public class ConcurrentLeftovers {
    interface Body { Object run() throws Throwable; }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + body.run()); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }

    static void timedOffer() {
        show("offer timed, room", () -> {
            LinkedBlockingDeque<String> q = new LinkedBlockingDeque<>(2);
            return q.offer("a", 1, TimeUnit.SECONDS) + " " + q;
        });
        show("offer timed, goes last", () -> {
            LinkedBlockingDeque<String> q = new LinkedBlockingDeque<>(3);
            q.add("a");
            q.offer("b", 0, TimeUnit.SECONDS);
            return q;
        });
        show("offer timed, full, no time", () -> {
            LinkedBlockingDeque<String> q = new LinkedBlockingDeque<>(1);
            q.add("a");
            return q.offer("b", 0, TimeUnit.SECONDS) + " " + q;
        });
        show("offer timed, full, runs out", () -> {
            LinkedBlockingDeque<String> q = new LinkedBlockingDeque<>(1);
            q.add("a");
            return q.offer("b", 5, TimeUnit.MILLISECONDS) + " " + q;
        });
        show("offer timed, full, negative", () -> {
            LinkedBlockingDeque<String> q = new LinkedBlockingDeque<>(1);
            q.add("a");
            return q.offer("b", -1, TimeUnit.SECONDS) + " " + q;
        });
        show("offer timed, room made while it waits", () -> {
            LinkedBlockingDeque<String> q = new LinkedBlockingDeque<>(1);
            q.add("a");
            boolean[] got = new boolean[1];
            Thread t = new Thread(() -> {
                try { got[0] = q.offer("b", 20, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            });
            t.start();
            while (t.getState() != Thread.State.TIMED_WAITING) Thread.sleep(1);
            String taken = q.take();
            t.join();
            return taken + " " + got[0] + " " + q;
        });
        show("offer timed, null", () -> new LinkedBlockingDeque<String>().offer(null, 1, TimeUnit.SECONDS));
        show("offer timed, null unit", () -> new LinkedBlockingDeque<String>().offer("a", 1, null));
        show("offer timed, flagged, room", () -> {
            LinkedBlockingDeque<String> q = new LinkedBlockingDeque<>(1);
            Thread.currentThread().interrupt();
            try { return q.offer("a", 1, TimeUnit.SECONDS) + " " + q; }
            catch (InterruptedException e) { return e + " " + q + " flag=" + Thread.currentThread().isInterrupted(); }
            finally { Thread.interrupted(); }
        });
        show("offer timed, interrupted while it waits", () -> {
            LinkedBlockingDeque<String> q = new LinkedBlockingDeque<>(1);
            q.add("a");
            String[] r = new String[1];
            Thread t = new Thread(() -> {
                try { r[0] = "" + q.offer("b", 20, TimeUnit.SECONDS); }
                catch (InterruptedException e) { r[0] = e + " flag=" + Thread.currentThread().isInterrupted(); }
            });
            t.start();
            while (t.getState() != Thread.State.TIMED_WAITING) Thread.sleep(1);
            t.interrupt();
            t.join();
            return r[0] + " " + q;
        });
    }

    static void keySetForEach() {
        show("forEach, newKeySet", () -> {
            ConcurrentHashMap.KeySetView<String, Boolean> set = ConcurrentHashMap.newKeySet();
            set.add("a");
            set.add("b");
            set.add("c");
            List<String> seen = new ArrayList<>();
            set.forEach(seen::add);
            Collections.sort(seen);
            return seen;
        });
        show("forEach, keySet of a map", () -> {
            ConcurrentHashMap<String, Integer> m = new ConcurrentHashMap<>();
            m.put("x", 1);
            List<String> seen = new ArrayList<>();
            ConcurrentHashMap.KeySetView<String, Integer> set = m.keySet();
            set.forEach(seen::add);
            return seen;
        });
        show("forEach, empty", () -> {
            int[] c = new int[1];
            ConcurrentHashMap.<String>newKeySet().forEach(k -> c[0]++);
            return c[0];
        });
        show("forEach, null", () -> {
            ConcurrentHashMap.<String>newKeySet().forEach((Consumer<String>) null);
            return "no";
        });
        show("forEach, removal inside", () -> {
            ConcurrentHashMap.KeySetView<String, Boolean> set = ConcurrentHashMap.newKeySet();
            set.add("a");
            set.add("b");
            set.forEach(set::remove);
            return set;
        });
        show("forEach, throws", () -> {
            ConcurrentHashMap.KeySetView<String, Boolean> set = ConcurrentHashMap.newKeySet();
            set.add("a");
            try {
                set.forEach(k -> { throw new IllegalStateException(k); });
                return "no";
            } catch (IllegalStateException e) {
                return e + " " + set;
            }
        });
        show("forEach, through Map.keySet's view type", () -> {
            Map<String, Integer> m = new ConcurrentHashMap<>();
            m.put("x", 1);
            List<String> seen = new ArrayList<>();
            m.keySet().forEach(seen::add);
            return seen;
        });
    }

    public static void main(String[] args) {
        timedOffer();
        keySetForEach();
    }
}
