# Lambdas and method references

javac compiles a lambda or method reference to `invokedynamic` with
`LambdaMetafactory.metafactory` or `altMetafactory` as the bootstrap method.
LazyVM links those call sites itself and supports no other bootstrap methods
(`BootstrapMethodError` names the unsupported one). There is no general
`MethodHandle` machinery.

## Linking a call site (`crates/vm/src/lambda`)

On the first execution of an `invokedynamic`, the VM reads, from the
caller's constant pool:

- the call site's name (the interface method) and type `(K1..Kn)I`: the
  captured values and the functional interface;
- the bootstrap arguments: the erased interface method type, the
  implementation method handle, and the instantiated method type; for
  `altMetafactory`, the flags, marker interfaces and bridge method types.

It then writes a class file (`classfile::writer`) for a final class
`<Caller>-Lambda-<n>` (`-` cannot appear in a Java name, so it cannot
collide with user classes or members) that implements the interface (and the markers, and
`Serializable` when flagged), with:

- one private final field per captured value and a constructor storing them;
- a static factory, which the call site calls from then on; a lambda that
  captures nothing has one instance, created by the class's `<clinit>`, so
  each evaluation of its expression yields the same object;
- the interface method and each bridge: load the captured values, load each
  argument, adapt it to the instantiated type and then to the implementation
  method's parameter, call the implementation (`invokestatic`,
  `invokevirtual`, `invokeinterface`, `invokespecial`, or `new` + `<init>`
  for constructor references), and adapt the result back.

The adaptations are the ones the `LambdaMetafactory` Javadoc lists:
primitive widening, boxing, unboxing and reference casts. For a reference
to a primitive, an argument must be a wrapper (`Integer` to `long`), while a
result may be any reference, cast to the base wrapper (`Number`, `Boolean`,
`Character`) and unboxed. Captured values are passed unconverted. Anything
else is a linkage error: `BootstrapMethodError` caused by
`LambdaConversionException`.

The class is defined like a loaded class, so it is parsed, checked and
verified the same way (`design/verifier.md`). It has the caller's loader. The linked factory is cached per instruction (caller, method, pc):
each `invokedynamic` is its own call site.

## Limits

- Malformed bootstrap arguments (a field handle, an argument of the wrong
  constant kind) are reported with `LambdaConversionException` as the
  cause, where Java 8 sometimes gives `IllegalAccessError` or
  `ClassCastException`.
- Serializable lambdas link and run, but serialization does not exist, so
  `writeReplace` is not generated.
- Generated classes call the caller's private `lambda$...` methods, so they
  count as part of the caller (`Class::host`) for access checks and for the
  verifier's `invokespecial` rule.
