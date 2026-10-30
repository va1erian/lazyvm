package thread;

/**
 * JLS 17.4: a volatile write is seen by a later read on another thread, and
 * everything written before it is seen too. Two threads pass a turn back and
 * forth through volatile fields of each kind and spin in loops that make no
 * calls, so only the VM's own safepoints can let the other thread run.
 */
public class VolatileHandoff {
    static final int ROUNDS = 15;

    static volatile int staticTurn;
    static int payload;

    volatile int intTurn;
    volatile long longTurn;
    volatile double doubleTurn;
    volatile boolean flag;
    volatile Object reference;
    final int[] box = {0};

    static void staticField() throws InterruptedException {
        int[] seen = new int[ROUNDS];
        Thread other = new Thread(() -> {
            for (int i = 0; i < ROUNDS; i++) {
                while (staticTurn != i * 2 + 1) {
                    // spin
                }
                seen[i] = payload;
                payload = i + 1000;
                staticTurn = i * 2 + 2;
            }
        });
        other.start();
        long sum = 0;
        for (int i = 0; i < ROUNDS; i++) {
            payload = i;
            staticTurn = i * 2 + 1;
            while (staticTurn != i * 2 + 2) {
                // spin
            }
            sum += payload;
        }
        other.join();
        long seenSum = 0;
        for (int v : seen) {
            seenSum += v;
        }
        System.out.println("static " + sum + " " + seenSum);
    }

    void instanceFields() throws InterruptedException {
        Thread other = new Thread(() -> {
            for (long i = 0; i < ROUNDS; i++) {
                while (longTurn != (i << 33) + 1) {
                    // spin
                }
                longTurn = (i << 33) + 2;
                while (doubleTurn != i + 0.5) {
                    // spin
                }
                doubleTurn = -(i + 0.5);
                while (!flag) {
                    // spin
                }
                flag = false;
                while (reference != box) {
                    // spin
                }
                reference = this;
                while (intTurn != 1) {
                    // spin
                }
                intTurn = 0;
            }
        });
        other.start();
        for (long i = 0; i < ROUNDS; i++) {
            longTurn = (i << 33) + 1;
            while (longTurn != (i << 33) + 2) {
                // spin
            }
            doubleTurn = i + 0.5;
            while (doubleTurn != -(i + 0.5)) {
                // spin
            }
            flag = true;
            while (flag) {
                // spin
            }
            reference = box;
            while (reference != this) {
                // spin
            }
            intTurn = 1;
            while (intTurn != 0) {
                // spin
            }
        }
        other.join();
        System.out.println("instance " + longTurn + " " + doubleTurn + " " + flag + " " + (reference == this) + " " + intTurn);
    }

    public static void main(String[] args) throws InterruptedException {
        staticField();
        new VolatileHandoff().instanceFields();
    }
}
