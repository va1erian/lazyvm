package s4;

import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Lambda identity per call site and the return-value adaptations of LambdaMetafactory. */
public class LambdaAdapt {
    static Object value;

    @SuppressWarnings("unchecked")
    static <T> T get() {
        return (T) value;
    }

    static Supplier<String> site() {
        return () -> "same";
    }

    public static void main(String[] args) {
        System.out.println("identity==identity " + (Function.identity() == Function.identity()));
        System.out.println("unary identity " + (UnaryOperator.identity() == UnaryOperator.identity()));
        System.out.println("same site " + (site() == site()));
        int k = 3;
        Supplier<Integer> a = () -> k;
        Supplier<Integer> b = () -> k;
        System.out.println("capturing distinct " + (a != b));
        value = 9;
        LongSupplier ls = LambdaAdapt::<Integer>get;
        System.out.println("generic Integer as long " + ls.getAsLong());
        value = (short) 7;
        IntSupplier is = LambdaAdapt::<Integer>get;
        System.out.println("heap-polluted Short as int " + is.getAsInt());
        value = 5L;
        System.out.println("heap-polluted Long as int " + is.getAsInt());
        value = 'x';
        try {
            System.out.println(is.getAsInt());
        } catch (ClassCastException e) {
            System.out.println("Character as int -> ClassCastException");
        }
        System.out.println("done");
    }
}
