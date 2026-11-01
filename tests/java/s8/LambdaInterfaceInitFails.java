package s8;

/** A call site whose interface fails to initialize: the same kind of error on every try. */
public class LambdaInterfaceInitFails {
    interface I {
        int X = boom();

        default void d() {
        }

        void run();
    }

    static int boom() {
        throw new IllegalStateException("boom");
    }

    static I site() {
        return () -> System.out.println("ran");
    }

    public static void main(String[] args) {
        for (int i = 0; i < 3; i++) {
            try {
                site().run();
                System.out.println("no error");
            } catch (Throwable t) {
                System.out.println(t.getClass().getName() + ": " + t.getMessage());
            }
        }
    }
}
