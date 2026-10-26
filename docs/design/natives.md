# The native contract between `lazyrt` and the VM

Every `native` method in `java/lazyrt` is implemented in Rust in
`crates/natives`, keyed by `class.name(descriptor)`. This file is the
contract. Adding or changing a native means changing it here, in the Java
declaration and in Rust, in the same PR. An unknown native throws
`UnsatisfiedLinkError` naming the method.

Rules for `lazyrt` Java code, until the stage named:

- No lambdas or method references until S4 (they need `invokedynamic`).
- No `assert` until S4.
- Helpers are package-private or live in `lazy.internal`; no new public or
  protected API in `java.*` (shared rule 2).
- Line separator: `System.lineSeparator()` comes from the
  `line.separator` property, which is `"\r\n"` on Windows and `"\n"` elsewhere.

## VM-known fields

The VM reads and writes these fields by name. Keep their names and types.

| Class | Field | Use |
|---|---|---|
| `java.lang.String` | `private final char[] value` | The UTF-16 contents; the VM creates strings by filling it |
| `java.lang.String` | `private int hash` | Cached hash, 0 when not computed |
| `java.lang.Class` | `private transient int vmClassId` | The VM's class id for this mirror |
| `java.lang.Throwable` | `private transient Object backtrace` | Frames recorded by `fillInStackTrace0` (an `int[]` the VM owns) |
| `java.lang.Throwable` | `private String detailMessage` | Set by the VM when it throws with a message |

## Natives

### `java.lang.Object`
- `public final native Class<?> getClass()`
- `public native int hashCode()` (identity hash)
- `protected native Object clone() throws CloneNotSupportedException` (arrays and `Cloneable`; shallow)
- `public final native void notify()`, `public final native void notifyAll()`
- `public final native void wait(long timeout) throws InterruptedException`

### `java.lang.Class`
- `public native String getName()` (binary name with dots; arrays as `[I`, `[Ljava.lang.String;`)
- `public native boolean isInstance(Object obj)`
- `public native boolean isAssignableFrom(Class<?> cls)`
- `public native boolean isInterface()`, `isArray()`, `isPrimitive()`
- `public native Class<? super T> getSuperclass()`
- `public native Class<?> getComponentType()`
- `public native int getModifiers()`
- `static native Class<?> getPrimitiveClass(String name)` (`"int"`, `"void"`, ...)
- `private static native Class<?> forName0(String name, boolean initialize) throws ClassNotFoundException`
- `public native boolean desiredAssertionStatus()` (`-ea`, application classes only)
- `private native T[] getEnumConstants0()` (calls the enum's static `values()`)

### `java.lang.System`
- `public static native void arraycopy(Object src, int srcPos, Object dest, int destPos, int length)`
- `public static native long currentTimeMillis()`
- `public static native long nanoTime()`
- `public static native int identityHashCode(Object x)`

### `java.lang.String`
- `public native String intern()`

### `java.lang.Throwable`
- `private native void fillInStackTrace0()`: records the current frames in
  `backtrace`, skipping the frames of `fillInStackTrace`/`fillInStackTrace0`
  and of the throwable's own constructors.
- `private native StackTraceElement[] getStackTrace0()`: builds the elements
  from `backtrace` (an empty array if there is none).

### `java.lang.StrictMath`
`public static native double` `sin`, `cos`, `tan`, `asin`, `acos`, `atan`,
`exp`, `log`, `log10`, `sqrt`, `cbrt`, `ceil`, `floor`, `rint`, `sinh`,
`cosh`, `tanh`, `expm1`, `log1p` (one `double`), and `atan2`, `pow`,
`hypot`, `IEEEremainder` (two `double`s).

### `java.lang.Float` and `java.lang.Double`
- `public static native int floatToRawIntBits(float value)`
- `public static native float intBitsToFloat(int bits)`
- `public static native String toString(float f)`
- `public static native float parseFloat(String s) throws NumberFormatException`
- `public static native long doubleToRawLongBits(double value)`
- `public static native double longBitsToDouble(long bits)`
- `public static native String toString(double d)`
- `public static native double parseDouble(String s) throws NumberFormatException`

### `java.lang.Character`
`public static native boolean` `isLetter`, `isDigit`, `isLetterOrDigit`,
`isWhitespace`, `isUpperCase`, `isLowerCase`, `isSpaceChar`,
`isAlphabetic` (all `(int codePoint)`), `public static native int toUpperCase(int codePoint)`,
`public static native int toLowerCase(int codePoint)`,
`public static native int digit(int codePoint, int radix)`. The `char`
overloads are Java and call these.

### `java.lang.Thread`
- `public static native void sleep(long millis) throws InterruptedException`
  (until S6 `currentThread()` is plain Java returning one main thread)

### `lazy.internal.VM`
- `public static native String getProperty(String key)` (null when unset)
- `public static native void exit(int status)`
- `public static native void gc()`
- `public static native long freeMemory()`, `totalMemory()`, `maxMemory()`
- `public static native int availableProcessors()`
- `public static native byte[] getResource(String name)` (null when absent)
- `public static native boolean isBootClass(Class<?> c)`
- Java, called by the VM: `static void uncaught(Throwable t)` prints
  `Exception in thread "main" ` and the stack trace to `System.err`.

### `lazy.internal.ConsoleOutputStream` and `ConsoleInputStream`
- `private static native void write0(int fd, byte[] b, int off, int len)` (fd 1 or 2)
- `private static native void flush0(int fd)`
- `private static native int read0(int fd, byte[] b, int off, int len)` (fd 0; -1 at end)
- `private static native int available0(int fd)`

Bounds are checked in Java before the native is called; the VM checks them
again and throws `IndexOutOfBoundsException` rather than trusting them.

## Known differences from Java 8

- A `NoClassDefFoundError` for a class missing from the class path has no
  cause; Java 8's carries a `ClassNotFoundException` from its class loader.
  Tests print only the error's class and message.
