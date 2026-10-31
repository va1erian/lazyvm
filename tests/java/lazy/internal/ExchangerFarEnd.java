package lazy.internal;

import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A far negative timeout is a past time like any other. A hand-written golden: the oracle hangs at these
 * values, against its Javadoc (concurrent.md, "What is not pinned").
 */
public class ExchangerFarEnd {
    static void alone(String label, long timeout, TimeUnit unit) throws Exception {
        Object[] answer = {"no answer"};
        Thread t = new Thread(() -> {
            try {
                answer[0] = new Exchanger<String>().exchange("a", timeout, unit);
            } catch (TimeoutException e) {
                answer[0] = "TimeoutException";
            } catch (InterruptedException e) {
                answer[0] = "InterruptedException";
            }
        });
        t.setDaemon(true);
        t.start();
        t.join(20000);
        System.out.println(label + " = " + answer[0]);
    }

    public static void main(String[] args) throws Exception {
        alone("most negative nanoseconds", Long.MIN_VALUE, TimeUnit.NANOSECONDS);
        alone("next to it", Long.MIN_VALUE + 1, TimeUnit.NANOSECONDS);
        alone("most negative days", Long.MIN_VALUE, TimeUnit.DAYS);
        alone("half way there", -Long.MAX_VALUE / 2, TimeUnit.NANOSECONDS);

        // A partner already waiting still meets it, whatever the timeout, and the far positive end waits.
        Exchanger<String> x = new Exchanger<>();
        String[] got = new String[1];
        Thread partner = new Thread(() -> {
            try {
                got[0] = x.exchange("from partner", Long.MAX_VALUE, TimeUnit.DAYS);
            } catch (InterruptedException | TimeoutException e) {
                got[0] = e.toString();
            }
        });
        partner.start();
        while (partner.getState() != Thread.State.WAITING && partner.getState() != Thread.State.TIMED_WAITING) {
            Thread.sleep(1);
        }
        System.out.println("met at the far end = " + x.exchange("from main", Long.MIN_VALUE, TimeUnit.NANOSECONDS));
        partner.join();
        System.out.println("partner got = " + got[0]);
    }
}
