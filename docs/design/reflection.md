# Reflection, annotations and generic signatures (S4-04 to S4-06)

The contract between `java/lazyrt` and `crates/natives` for
`java.lang.reflect`, runtime annotations and generic signatures. As with
[`natives.md`](natives.md), a change here changes both sides in one PR.

The split: the VM enumerates members, reads and writes values, invokes, and
hands out raw attribute bytes and constant pool entries. Everything else —
filtering, inheritance walks, argument conversion, annotation parsing and
generic signature parsing — is Java, written from the Javadoc.

## Member objects

The VM creates `Field`, `Method` and `Constructor` objects without running
their constructors, filling these private fields by name:

| Class | Fields the VM fills |
|---|---|
| `java.lang.reflect.Field` | `Class<?> clazz`, `String name`, `Class<?> type`, `int modifiers`, `int slot`, `String signature`, `byte[] annotations` |
| `java.lang.reflect.Method` | `Class<?> clazz`, `String name`, `Class<?>[] parameterTypes`, `Class<?> returnType`, `Class<?>[] exceptionTypes`, `int modifiers`, `int slot`, `String signature`, `byte[] annotations`, `byte[] parameterAnnotations`, `byte[] annotationDefault` |
| `java.lang.reflect.Constructor` | `Class<T> clazz`, `Class<?>[] parameterTypes`, `Class<?>[] exceptionTypes`, `int modifiers`, `int slot`, `String signature`, `byte[] annotations`, `byte[] parameterAnnotations` |

`slot` is the member's index in its class (VM-internal; Java only passes it
back). `signature` is the `Signature` attribute or null. The `byte[]` fields
are the bodies of `RuntimeVisibleAnnotations`,
`RuntimeVisibleParameterAnnotations` and `AnnotationDefault`, or null.
Objects of these classes may also be created and copied by Java code (for
`getMethods` copies, for example) as long as the fields are kept.

## Natives

### `java.lang.Class`
- `private native Field[] getDeclaredFields0(boolean publicOnly)`
- `private native Method[] getDeclaredMethods0(boolean publicOnly)` (no `<init>`, no `<clinit>`)
- `private native Constructor<T>[] getDeclaredConstructors0(boolean publicOnly)`
- `private native Class<?>[] getInterfaces0()` (direct superinterfaces, in order)
- `private native String getGenericSignature0()` (class `Signature` attribute or null)
- `private native byte[] getRawAnnotations()` (`RuntimeVisibleAnnotations` or null)
- `private native Class<?> getDeclaringClass0()` (the outer class from `InnerClasses`, null for top-level, local and anonymous classes)

Members come in class-file order. Arrays and primitives have none.

### `java.lang.reflect.Field`
- `private static native Object get0(Field f, Object obj)`: the value, boxed
  for primitives. For a static field `obj` is ignored and the class is
  initialized first.
- `private static native void set0(Field f, Object obj, Object value)`:
  `value` is already of the field's exact type (boxed for primitives; Java
  does the widening and the checks of `Field.set`).

### `java.lang.reflect.Method`
- `private static native Object invoke0(Method m, Object obj, Object[] args)`:
  `args` already match the parameter types exactly (boxed for primitives;
  Java converts and checks counts, receivers and nulls first). Instance
  methods that are not private are selected on the receiver's class, as
  `Method.invoke` specifies. Returns the result boxed, or null for `void`.
  An exception thrown by the method propagates as is; the Java caller wraps
  it in `InvocationTargetException`.

### `java.lang.reflect.Constructor`
- `private static native Object newInstance0(Constructor<?> c, Object[] args)`:
  initializes the class, allocates, runs the constructor. Same argument rules.

### `java.lang.reflect.Array`
- `private static native Object newArray(Class<?> componentType, int length)`
- `public static native int getLength(Object array)` (`IllegalArgumentException` for a non-array)
- `private static native Object get0(Object array, int index)` (boxed)
- `private static native void set0(Object array, int index, Object value)`
  (exact element type; Java widens)

### `lazy.internal.VM`, constant pool access for annotation parsing
- `static native String poolUtf8(Class<?> c, int index)`
- `static native int poolInt(Class<?> c, int index)`
- `static native long poolLong(Class<?> c, int index)`
- `static native float poolFloat(Class<?> c, int index)`
- `static native double poolDouble(Class<?> c, int index)`

Each throws `IllegalArgumentException` when the entry is missing or of
another kind.

### `lazy.internal.VM`, annotation implementation classes
- `static native Class<?> annotationClass(Class<?> annotationType)`: a
  generated class `<A>$$Annotation` that extends
  `lazy.internal.AnnotationBase` and implements `A`, with a constructor
  `(Class, String[], Object[])` passed to `AnnotationBase`'s, and one method
  per abstract method of `A`, in class-file order: method `i` returns
  `value(i)` converted to its return type (unboxed for primitives).

## `lazy.internal.AnnotationBase` (Java)

- `protected AnnotationBase(Class<? extends Annotation> type, String[] names, Object[] values)`
- `protected final Object value(int i)`: the stored value, a clone for arrays.
- `annotationType()`, `equals`, `hashCode` and `toString` as the
  `java.lang.annotation.Annotation` Javadoc specifies.

Names and values are in the order of the annotation type's
`getDeclaredMethods()`, which is class-file order. Java builds the values,
filling in defaults from `AnnotationDefault`, and constructs the generated
class through `Constructor.newInstance`.

## Known differences from Java 8

- `Annotation.toString` lists members in Java 8's order (members with
  defaults in hash order, then the others in pair order; `HashOrder`), but
  when two member names fall in the same hash bucket Java 8's order is not
  reproducible: it is the same for every declaration order tried, so it
  depends on JVM internals. LazyVM keeps declaration order for ties.
- A `Class` or enum element whose class cannot be loaded throws
  `TypeNotPresentException` when the element is read, as in Java 8; an array
  element with one missing class is missing as a whole.
