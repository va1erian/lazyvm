package s8;

/** A call site reached again while its lambda's interface initializes gets a finished object. */
public class LambdaDuringInterfaceInit {
    interface I {
        Object O = site();

        default void d() {
        }

        void run();
    }

    static I site() {
        return () -> System.out.println("ran");
    }

    public static void main(String[] args) {
        I i = site();
        i.run();
        System.out.println(I.O == null);
        ((I) I.O).run();
    }
}
