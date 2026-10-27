// TIMEOUT: 120
// VMARGS: -Xmx16m
package s2;

public class GcChurn {
    static class Node {
        final int value;
        Node next;
        Node(int value, Node next) { this.value = value; this.next = next; }
    }

    static class Point {
        final int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
    }

    static long churn() {
        Object[] window = new Object[100];
        long checksum = 0;
        for (int i = 0; i < 2000000; i++) {
            Object o;
            switch (i % 4) {
                case 0: o = new Point(i, i * 3); break;
                case 1: o = new int[i % 16 + 1]; ((int[]) o)[0] = i; break;
                case 2: o = new Node(i, null); break;
                default: o = "s" + (i % 1000); break;
            }
            int slot = i % 100;
            Object old = window[slot];
            if (old != null) checksum += describe(old);
            window[slot] = o;
        }
        for (int i = 0; i < window.length; i++) checksum += describe(window[i]);
        return checksum;
    }

    static long describe(Object o) {
        if (o instanceof Point) return ((Point) o).x + ((Point) o).y;
        if (o instanceof int[]) return ((int[]) o)[0] + ((int[]) o).length;
        if (o instanceof Node) return ((Node) o).value;
        return ((String) o).length();
    }

    static Node build(int n, int seed) {
        Node head = null;
        for (int i = 0; i < n; i++) head = new Node(i * seed, head);
        return head;
    }

    static long sum(Node n) {
        long s = 0;
        for (; n != null; n = n.next) s += n.value;
        return s;
    }

    public static void main(String[] args) {
        System.out.println("checksum " + churn());
        Node list = build(100000, 1);
        System.out.println("list sum " + sum(list));
        list = null;
        System.gc();
        Node second = build(100000, 3);
        System.out.println("second sum " + sum(second));
        int len = 0;
        for (Node n = second; n != null; n = n.next) len++;
        System.out.println("length " + len);
        long arrays = 0;
        for (int i = 0; i < 2000; i++) {
            long[] big = new long[1000];
            big[i % 1000] = i;
            arrays += big[i % 1000] + big.length;
        }
        System.out.println("arrays " + arrays);
        System.out.println("after churn " + churn());
        System.out.println("done");
    }
}
