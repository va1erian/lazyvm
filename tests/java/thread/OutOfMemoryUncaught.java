// VMARGS: -Xmx8m
package thread;

/** Main dies of a full heap: it is reported as usual and the status is 1. */
public class OutOfMemoryUncaught {
    static final class Node {
        final Node next;
        final byte[] data = new byte[256 << 10];

        Node(Node next) {
            this.next = next;
        }
    }

    public static void main(String[] args) {
        Node keep = null;
        while (true) {
            keep = new Node(keep);
        }
    }
}
