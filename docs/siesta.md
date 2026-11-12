# Siesta: a small language for LazyOS

Siesta is a proposed source language with Java's syntax and types, cut to
the smallest compiler that can still write any LazyOS program, plus
`async`/`await` and a few Smalltalk habits where they cost almost nothing.
It compiles to version 52 class files that run on LazyVM and on any Java 8
VM. Its compiler, `siestac`, is a Rust program, so a LazyOS machine builds
programs for LazyVM in milliseconds without a Java compiler. It is also the
language of LazyRAD (section 11); `async`/`await` make LazyOS's
asynchronous APIs read like blocking code
([`siesta-async.md`](siesta-async.md)); and the features that shorten
`lazy.ui` and Messenger apps, with a proposed `lazy.msg`, are in
[`siesta-apps.md`](siesta-apps.md). It is a proposal: nothing here is
decided until the owner confirms it in [`decisions.md`](decisions.md), and
the stage it implies is S16 in [`plan-2.md`](plan-2.md).

## 1. Why, and why not something else

[`compiler.md`](compiler.md) weighs three ways to compile on LazyOS: a stock
JDK (A), javac 8 on LazyVM (B), and our own Java compiler (C, turned down as
months of work and a second javac). Siesta is not C: it keeps Java's
surface but drops the parts of the JLS that cost the most to implement
(section 3), so its compiler stays small.

| | A: JDK on LazyOS | B: javac 8 on LazyVM | Siesta |
|---|---|---|---|
| What runs the compiler | HotSpot | LazyVM, interpreted | native Rust |
| Size on LazyOS | hundreds of MiB | `tools.jar` plus `javax.*` additions to `lazyrt` | one static binary under 2,000,000 bytes, stripped and uncompressed |
| Language | all of Java | all of Java 8 | Java's syntax, a smaller rule set, `async`/`await` |
| Ready | when HotSpot runs on LazyOS | after S4, S5, S8, S11 and the D6 "Compiler support" row | needs nothing from LazyVM beyond what is done |

## 2. Goals

- **No glue between Siesta and Java.** A call, a lambda, a cast, a generic
  type and an exception are written as in Java, so the Javadoc reads
  straight into Siesta and Java code calls Siesta classes with no
  adapters. What Siesta adds lowers to plain Java-visible members.
- **Least code for a complete language.** A feature is in when a LazyOS
  app or a LazyRAD form needs it and nothing already in the
  language does the job; among two ways to provide it, the one that needs
  no new analysis wins. The ledger in section 3 is the rule applied.
- **Fast to compile.** One pass collects declarations, one checks and
  emits each method; the class path is read lazily. Target: a 1,000-line
  program in under 200 ms on LazyOS under QEMU, measured, not assumed
  (S16-10).
- **No runtime library.** Programs need only the Java SE 8 API `lazyrt`
  provides (and `lazy.async` once they `await`, siesta-async.md section 4),
  so a Siesta jar runs on the oracle JVM unchanged, which is how it is
  tested.
- **Plain class files.** Version 52 with `StackMapTable`, passing LazyVM's
  verifier and the oracle's `-Xverify:all`; `invokedynamic` only through
  `LambdaMetafactory.metafactory` (D1).

## 3. The ledger

Estimated lines of Rust for each feature beyond the core (lexer, parser,
expressions, calls, classes and code generation), and why it is in or out.

| Feature | In | Lines | Why |
|---|---|---|---|
| Java calls, fields, `new`, casts, `instanceof`, operators, `++`, compound assignment | yes | core | the API cannot be used without them |
| overload resolution, boxing, varargs | yes | 600 | the same |
| generic types, `Signature` reading and writing, substitution | yes | 700 | collections and streams |
| generic classes and methods of your own, simple bounds | yes | 250 | reuses the reader's type variables; only parsing and `Signature` writing are new |
| one-way inference of method type arguments | yes | 500 | `collect(Collectors.toList())`, `Collections.emptyList()` without casts |
| lambdas and method references | yes | 600 | every callback in `lazy.*` and `java.util.function` |
| trailing lambda `button.onClick { save(); }` | yes | 60 | every event, menu entry and subscription in an app ([`siesta-apps.md`](siesta-apps.md)) |
| lambdas that change captured locals | yes | 120 | a Smalltalk block habit; the counter in a click handler |
| classes, interfaces with default and static methods, abstract classes, static nested classes | yes | core | apps and libraries |
| `enum` with fields and constructors | yes | 200 | state machines, `switch` cases |
| bridge methods | yes | 120 | `implements Comparable<Point>` and overriding generic methods |
| annotations with constant arguments | yes | 150 | `lazy.msg` services (D8) |
| `if`, `while`, `do`, classic and for-each `for`, `break`, `continue` | yes | core | |
| `switch` with arrows (`case 1, 2 ->`), on `int`, `char`, `String` and enums, no fall-through | yes | 200 | lowered as an `if` chain: no `tableswitch` sizing, no fall-through analysis |
| `x instanceof T t` binding | yes | 60 | the cast that always follows; scoping only, no flow analysis |
| `try`/`catch` (multi-catch), `finally`, `throw` | yes | 300 | the API throws |
| "missing `return`" check (JLS 14.21, simplified) | yes | 80 | the verifier rejects a method that can fall off its end |
| `synchronized` methods | yes | 5 | a flag (`ACC_SYNCHRONIZED`) |
| `async`/`await` | yes | 900 | siesta-async.md |
| top-level statements and methods (a script needs no class) | yes | 80 | Smalltalk's workspace; scripts and tools |
| cascade `x..add(a)..add(b)` | yes | 40 | Smalltalk's `;`, for builders that are not fluent |
| list and map literals `[a, b]`, `[k: v]`, `[:]` | yes | 120 | the collections every program builds |
| string interpolation `"${x}"` | yes | 100 | the most common string a program builds |
| calling a method on a primitive (`n.toString()`) | yes | 30 | boxes the receiver; Smalltalk's "everything answers messages" |
| local variables without an initialiser | no | 400 | definite assignment (JLS 16) is a whole analysis; every local is initialised where it is declared |
| inner (non-static), local and anonymous classes | no | 800 | outer-instance capture and synthetic accessors; lambdas and named classes cover them |
| try-with-resources | no | 150 | `finally` writes it |
| labelled `break`/`continue`, old `switch` with fall-through | no | 150 | rare; arrow `switch` and a flag cover them |
| checked-exception checking | no | 300 | the JVM never checks it either |
| `synchronized` blocks, `static { }` initialisers, `assert` | no | 150 | field initialisers and `java.util.concurrent` cover them |
| wildcard capture, intersection types, full JLS 18 inference | no | 2,000+ | the largest cost in javac; one-way inference plus `(T)` casts covers real code |

The "no" rows are what keep `siestac` near 9,400 lines, against about
13,500 if they were all in.

## 4. A taste

```siesta
package demo;
import java.util.*;
import java.util.stream.Collectors;

// A file's top-level statements form its main method; `args` is in scope.
var words = ["lazy", "os", "siesta", "vm", "lazy"];
Map<String, Integer> counts = new LinkedHashMap<>();
for (var w : words) {
    counts.merge(w, 1, Integer::sum);
}
System.out.println("counts: ${counts}");

List<String> longWords = words.stream()
    .filter(w -> w.length() > 2)
    .map(String::toUpperCase)
    .collect(Collectors.toList());
System.out.println(longWords);

int clicks = 0;
Runnable click = () -> clicks++;     // a lambda may change what it captures
click.run();
System.out.println(describe(Shape.of(args.length)) + " after ${clicks} click");

String describe(Object o) {
    if (o instanceof Circle c) return "circle r=${c.r}";
    if (o == null) return "nothing";
    return o.toString();
}

interface Shape {
    double area();
    static Shape of(int n) { return n == 0 ? new Circle(1.0) : new Square(n); }
}

class Circle implements Shape {
    public final double r;
    Circle(double r) { this.r = r; }
    double area() { return Math.PI * r * r; }
}

class Square implements Shape {
    public final double side;
    Square(double side) { this.side = side; }
    double area() { return side * side; }
    String toString() { return "square side=${side}"; }
}
```

## 5. Syntax

Siesta's grammar is Java 8's (JLS chapters 3, 4, 6 to 10, 14 and 15) with
the "no" rows of section 3 removed and these additions:

```ebnf
topLevel  = typeDecl | method | stmt ;           (* a file may mix all three *)
modifier  = javaModifier | "async" ;
local     = ( type | "var" ) Ident "=" expr ";" ; (* an initialiser is required *)
switch    = "switch" "(" expr ")" "{" { "case" label { "," label } "->" ( block | stmt ) }
            [ "default" "->" ( block | stmt ) ] "}" ;
label     = literal | "null" | qualifiedEnumConstant ;
relational = ... | expr "instanceof" type [ Ident ] ;
unary     = ... | "await" unary ;
lambda    = [ "async" ] javaLambda ;
call      = ... | call [ "async" ] "{" [ Ident { "," Ident } "->" ] { stmt } "}" ;  (* trailing lambda *)
postfix   = ... | ".." Ident args ;               (* cascade *)
primary   = ... | "[" [ expr { "," expr } ] "]" | "[" ":" "]"
          | "[" expr ":" expr { "," expr ":" expr } "]" ;
string    = '"' { char | "${" expr "}" } '"' ;
```

Reserved words are Java's, minus the ones of the cut features (`goto`,
`const`, `assert`, `native`, `transient`, `volatile`, `strictfp`), plus
`var`, `async` and `await`.

**Defaults** follow Smalltalk, not Java, because they remove the most
boilerplate: a class, method or constructor with no access modifier is
`public`; a field with none is `private`. `protected` and package access
exist for overriding Java members that have them, and `private` narrows a
method.

**A file's top level** is a class named after the file in UpperCamelCase
(`hello_world.sie` is `HelloWorld`; if the file declares that class
itself, the top level joins it). Top-level methods and `var`s are its
static members, and top-level statements its `main(String[] args)`. An
unqualified name reaches the class's own members, then the top-level
methods and variables of every file compiled with it, then `import static`
members, so an app's files share one set of state without passing an
`app` object around. A file with top-level statements initialises its
variables in order as `main` runs; a file without any (shared state and
helpers) initialises them in its static initialiser, on first use. A
top-level `var` takes its type from its initialiser whichever file it is
in; one whose type depends on itself is an error.

## 6. Types

- **Java's types and conversions**: primitives with numeric promotion
  (JLS 5.6), boxing and unboxing (5.1.7, 5.1.8), string conversion
  (5.1.11), casts (5.5). On an interpreter, unboxed arithmetic is the
  difference between fast and slow loops.
- **Locals** have a declared type or their initialiser's (`var`); fields,
  parameters and results are written out, so each method is checked on
  its own.
- **Generics** are read from `Signature` attributes (JVMS 4.7.9.1) and
  written for Siesta's own generic classes and methods; members are
  substituted with the receiver's type arguments and `siestac` inserts the
  `checkcast` erasure needs. Wildcards are collapsed when read
  (`? extends T` and `? super T` as `T`, `?` as the bound) and may be
  written in declarations, where they mean what they mean to Java callers.
- **Method type arguments** are inferred one way: fresh variables per call,
  argument types unified with parameter types left to right (a lambda's
  parameters from what is known, its result from its body), the expected
  type last. A variable nothing pins down becomes its bound, and a cast
  fixes it. Diamond `new HashMap<>()` takes the expected type.
- **Literals.** `["a", "b"]` is an `ArrayList<String>`, `[k: v]` and `[:]` a
  `LinkedHashMap` (stable order for goldens), typed by the expected type,
  else by the join of the elements. Array initialisers are Java's
  (`int[] a = {1, 2}`).

## 7. Using the Java API

- **Lookup** as in Java: `import`, `import static`, `java.lang.*` implicit.
- **Overloads** in Java's phases, simplified (JLS 15.12.2): by name and
  argument count; applicable by subtyping and widening; then with boxing;
  then with varargs. The most specific wins; a tie is an error naming the
  candidates. A lambda is applicable to a functional interface of its
  arity; between `void` and value-returning ones (`submit(Runnable)` and
  `submit(Callable)`), its body decides.
- **Functional interfaces** come from class files: one abstract method that
  is not a public `Object` method, counting inherited ones.
- **Extending Java classes** and implementing generic interfaces emits the
  bridges erasure needs, as javac does; an abstract method left
  unimplemented is an error.
- **Annotations** with constant arguments become `RuntimeVisibleAnnotations`
  when retained at run time, so `lazy.msg`'s `@Service` and `@Subscribe`
  work.
- **Java calls Siesta** with no adapter: every Siesta member is a plain JVM
  member, top-level code is a class with static members, and an `async`
  method returns `CompletionStage<T>`.

## 8. Lowering

| Siesta | Class file |
|---|---|
| top level of `foo.sie` | static fields, static methods and `main(String[])` of class `Foo`; a variable only the file's statements use is a local of `main` |
| `final` static field with a literal of its type | a constant: reads load the value, so they do not initialise the class (JLS 12.4.1) |
| constant `int` expression (`TILE - 1`) | its value (JLS 15.29); an `int` constant an operator widens is loaded as `long`, `float` or `double` |
| lambda | a private synthetic `lambda$<method>$<n>` (static unless it uses `this`) and `invokedynamic` to `LambdaMetafactory.metafactory` |
| method reference | the same `invokedynamic` with the referenced method's handle, no synthetic method |
| captured local changed by a lambda or after capture | lives in a one-element array shared with the lambda |
| `switch` | an `if` chain: `equals` for strings, `if_icmpeq` for integers and chars, `if_acmpeq` for enum constants |
| `x instanceof T t` | `instanceof`, then `checkcast` into a new local scoped to the true branch |
| `try`/`finally` | the `finally` block copied on each exit path, plus a catch-all that rethrows (no `jsr`) |
| `"a ${x}"`, string `+` | a `StringBuilder` `append` chain |
| `x..m(a)` | `dup`, call, `pop` the result |
| `[a, b]`, `[k: v]` | `new ArrayList(n)` / `new LinkedHashMap()` then `add`/`put` |
| generic result | `checkcast` to the substituted type |
| `enum` | a `final` class extending `java.lang.Enum` with `values()` and `valueOf(String)` |
| generic declaration | a `Signature` attribute; the code is erased as javac erases it |

**Stack maps.** Version 52 classes need a `StackMapTable` (JVMS 4.7.4), and
LazyVM's verifier does not fall back to inference (D1,
[`design/verifier.md`](design/verifier.md)). Siesta's control flow is
structured and every local is initialised where it is declared, so the
generator knows the live locals and their types at every jump target and
writes a `full_frame` from its scope, with no inference pass and no
definite-assignment analysis. The only values on the stack at a join are
the results of `?:`, `&&`, `||` and comparisons used as values, typed with
the expected type when there is one, else the join of the branches.
Constructor arguments containing a branch are evaluated into locals before
`new`. `SourceFile`, `LineNumberTable` and `LocalVariableTable` are always
written, so stack traces and JDWP (S11) point at `.sie` lines.

## 9. The compiler

One crate, `crates/siesta`: a library (parser, checker, code generator)
and the `siestac` binary, depending only on `crates/classfile`. That crate
already parses class files and reads jars (with `miniz_oxide`); its writer
gains `StackMapTable`, `BootstrapMethods`, `LineNumberTable`, `Signature`,
annotations and a stored-jar writer rather than a second writer being
added (shared rule 4). Malformed source, class files and jars are
diagnostics, never panics. LazyRAD links the library to edit view files
(section 11), so there is one Siesta parser.

| Module | Job | Estimated lines |
|---|---|---|
| `lex`, `parse/`, `ast`, `diag` | tokens, interpolation, Java's grammar minus the cuts, `file:line:col` messages | 2,000 |
| `types` | subtyping, erasure, boxing, member lookup, functional interfaces | 900 |
| `infer`, `overload` | sections 6 and 7 | 1,100 |
| `check/`, `anf` | names, types, captures, reachability, `async` linearisation, typed IR | 2,100 |
| `classpath`, `sig` | jars and directories, `Signature` reading and writing, the class-file universe | 900 |
| `gen/` | bytecode, max stack and locals, stack maps, lambdas, bridges, `async` frames | 2,000 |
| `emit`, `main` | class and jar output, the command line | 400 |

About 9,400 lines, every file under 500.

**The 2 MB budget.** The stripped static musl `siestac` stays under
2,000,000 bytes, uncompressed; `python tools/size.py --siestac` checks it
like the VM's own budget, and `siestac` does not count against D14. The
levers: no dependency beyond `classfile` (whose only one, `miniz_oxide`,
the VM already carries); no `regex`, `serde` or `toml`; a release profile
for this binary with `opt-level = "s"`, LTO and one codegen unit. If a
build goes over, the costliest feature moves to the "no" rows; the budget
is not raised.

```bash
siestac [-cp path] [-d out | -o app.jar] files...
siestac run [-cp path] file.sie [args...]   # compile to a temporary directory, then exec lazyvm
siestac check files...                         # diagnostics only
```

`-o` writes a jar (stored, since packages are compressed whole) whose
`Main-Class` is the first file's class, for `lazyvm -jar`. The
class path defaults to the `lazyrt.jar` next to `lazyvm`. All files of one
call are compiled together, in any order. On LazyOS, `siestac` is a static
musl program staged into the image next to `lazyvm` by the LazyOS repo.

## 10. Testing and evidence

Siesta sources cannot be blessed on the oracle, but their class files run
there, and most Siesta programs are one edit away from Java:

- **Mirrors.** A test `tests/siesta/<area>/<Name>.sie` has a `<Name>.java`
  beside it wherever Java can say the same thing, which is most tests
  since the syntax is Java's; its `.out` is blessed on the oracle
  (`tools/golden.py --bless`) and the Siesta program must print the same.
- **Goldens.** Tests of what Java cannot say (`await`, top-level code,
  literals, cascades) have a hand-written, reviewed `.out`, like `lazy.*`
  goldens (D9).
- **Two VMs.** `tools/siesta_test.py` compiles each test once and runs it on
  the oracle with `-Xverify:all` and on LazyVM; both must match the `.out`.
  The oracle's verifier checks every stack map `siestac` writes.
- **Errors and fuzzing.** `tests/siesta/bad/*.sie` with the expected
  message in `.err`, each cut feature among them with its message; a
  `cargo fuzz` target over the lexer, parser and checker, which must never
  panic.
- **LazyOS.** `tools/lazyos_test.py --siesta` runs `siestac` inside LazyOS
  on the same tests.
- **Speed.** `bench/siesta` is the benchmark suite (`bench/bench`) written
  again in Siesta with the same class names; `tools/bench.py run --siesta`
  times it and `bench.py compare` sets it against javac's build, checksums
  included.

**Measured against javac** (Windows, 2026-11-12, medians of 7 runs, the
same checksums everywhere). The suite is 403 code lines in Siesta against
538 in Java (-25 %), and its classes are 20,474 bytes against 26,046
(9.8 KB against 12.5 KB compressed together; javac's nested classes and
private constructors are the difference). siestac
compiles it in 15 ms, javac in 280 ms (each with its start-up).

| benchmark | LazyVM, interpreter | LazyVM, JIT | HotSpot 8 |
|---|---|---|---|
| blit | -1.4 % | +0.7 % | -1.3 % |
| calls | -0.6 % | -4.8 % | +1.5 % |
| casemap | -1.1 % | -0.6 % | +5.7 % |
| maps | -1.4 % | -2.1 % | +4.1 % |
| noise | +0.1 % | +1.5 % | -1.1 % |
| numeric | 0.0 % | +2.6 % | -3.0 % |
| tick | -1.1 % | +3.3 % | +1.2 % |
| worldgen | -0.7 % | +0.3 % | +0.5 % |

Each figure is Siesta's time against javac's; the JIT and HotSpot runs vary
by up to 7 % between runs (HotSpot's take 3 to 14 ms), so all of them are
within noise. Before the three code-generation changes in the table above
(constants, script locals and folding), Blit was 11.5 % slower on the
interpreter: it read `TILE` with `getstatic` where javac had `bipush`.

**Clean room.** `siestac` is written from the JVMS (chapters 4 and 6) and
the JLS sections cited above. `javap` of class files we compiled ourselves,
javac's output for a Java program we wrote included, is allowed (shared
rule 1) and is how a lowering is compared. We do not read the source of
javac or of other JVM-language compilers.

## 11. LazyRAD

LazyRAD (LazyOS `docs/lazyrad/`) today pairs a TOML form (`.lfm`) with a
Rhai script whose functions are found by name (`hello_button_click`). With
Siesta, LazyRAD changes to fit the language: a form is ordinary Siesta
code, the compiler knows nothing about LazyRAD, and there is no naming
convention, reflection or form loader at run time. A form is two files; the
designer owns the first and rewrites it on every change.

```siesta
// main_form.view.sie: written by the LazyRAD designer, not by hand
import lazy.ui.*;

class MainFormView {
    public final Window window = Ui.window("Hello", 400, 160);
    public final Edit nameEdit = Ui.edit("");
    public final Button helloButton = Ui.button("Say hello");
    public final Label resultLabel = Ui.label("");

    MainFormView() {
        window.content(Ui.fixed()
            ..place(nameEdit, 16, 16, 240, 24, Anchor.STRETCH_HORIZONTAL)
            ..place(helloButton, 264, 16, 120, 28, Anchor.TOP_RIGHT)
            ..place(resultLabel, 16, 56, 368, 20, Anchor.STRETCH_HORIZONTAL));
    }
}
```

```siesta
// main_form.sie: yours
class MainForm extends MainFormView {
    int clicks = 0;

    MainForm() {
        helloButton.onClick(() -> greet());
        nameEdit.onChange(text -> helloButton.enabled(!text.isEmpty()));
    }

    void greet() {
        clicks++;
        resultLabel.text("Hello, ${nameEdit.text()}! (${clicks})");
    }
}

Ui.run(new MainForm().window);
```

- **Events are lambdas**, attached where the programmer can see them. A
  double-click on a control in the designer adds `helloButton.onClick(() ->
  {});` to the constructor and puts the cursor in the braces.
- **Controls are typed fields**, so completion, renames and errors come
  from the compiler (`siestac check`), not from run-time lookups.
- **The view file has a fixed shape**: one field per control initialised
  by a `Ui` factory, and one cascade of `place` calls. The designer reads
  it back with the `siesta` library's parser and refuses to open a view that was
  edited outside that shape.
- **Running and packaging.** F5 is `siestac run`; Make LazyOS App packages
  the jar `siestac -o` writes, depending on `lazyvm`. The debugger is JDWP
  (S11) once LazyVM has it.
- **What `lazy.ui` needs.** A positioned layout (`Ui.fixed()` with
  `place(widget, left, top, width, height, anchor)` and LazyRAD's twelve
  anchors), and widgets for the LazyRAD control kinds it lacks
  (`ToggleButton`, `Hyperlink`, `MultilineEdit`, `NumberField`,
  `RadioGroup`, `GroupBox`, `Separator`, `PictureBox`; a LazyRAD `Timer` is
  `Ui.every`). That is S9 work, not compiler work.
- **What LazyRAD's Rhai library becomes.** `msg_box`, `file_read_text`,
  `random` and the rest are the Java API and `lazy.*`. Where something is
  missing it is added to `lazy.*`, not to a Siesta runtime.

## 12. Open questions for the owner

1. Confirm the language and the name ("Siesta", files `.sie`, compiler
   `siestac`), and the S16 stage.
2. The ledger's cuts: confirm, in particular locals without initialisers,
   inner and anonymous classes, and try-with-resources, which Java users
   will reach for first.
3. Should `siestac` run in-process from `lazyvm` (`lazyvm hello.sie`)?
   That would count against D14; the proposal keeps them apart.
4. LazyRAD: confirm that new LazyRAD projects use Siesta (section 11), what
   happens to existing Rhai projects, and that `Ui.fixed()` and the LazyRAD
   widgets join `lazy.ui`'s scope as S9 items.
