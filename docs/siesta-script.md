# Siesta as an embedded script language

Status: proposal, part of S16 in [`plan-2.md`](plan-2.md). The language is
[`siesta.md`](siesta.md); `async`/`await` is [`siesta-async.md`](siesta-async.md).
The first host is miniscent's level scripting (`E:\repos\miniscent`,
`docs/scripting.md` and `docs/scripting-language.md` there); nothing in it
changes until miniscent's owner adopts this there.

## 1. What a game host needs

Miniscent scripts levels in a small Smalltalk-style language of its own
(`crates/mscent-script`, about 3,100 lines) and chose it over Rhai and over
embedding LazyVM for reasons Siesta has to respect:

| Miniscent needs | What Siesta gives it |
|---|---|
| no compile step in the agent loop | the front end runs in-process at level load, in milliseconds, like today's |
| a script really suspends (`wait:` parks one handler) | `async` lambdas and `await`, lowered to the host VM's own task parking |
| typos fail at load, even in handlers that never fire | static types: an unknown method, a call on the wrong kind of handle and an unknown level name in a literal are load errors |
| determinism and replays | the language has no clock, threads or hash-ordered iteration; budgets stay the host VM's |
| untrusted input, never a panic | the front end's limits and fuzzing (section 7) |
| small, no dependencies | the front-end crates have none (section 8) |
| `Game` stays `Clone` | the host VM is unchanged in kind: miniscent keeps its heap, frames and scheduler |

So Siesta replaces miniscent's syntax, parser and checker, not its VM: the
same front end feeds two back ends, `siestac`'s class files for LazyVM and a
host's own bytecode.

## 2. One front end, two back ends

The compiler is split into crates with one job each (siesta.md section 9):

| Crate | Job | Depends on |
|---|---|---|
| `siesta-syntax` | lexer, parser, AST, diagnostics | nothing |
| `siesta-check` | names, types, inference, lambdas, `async` linearisation; produces typed IR | `siesta-syntax` |
| `siestac` | the JVM back end: class path, class files, jars | the two above and `classfile` |

`siesta-check` sees types only through a `Universe` trait: look up a type, its
members, its single abstract method, whether a type is awaitable, and check
a name literal. `siestac` implements it from class files; a host implements
it from interface files (section 4). The typed IR it hands a back end has
calls already resolved, captured variables marked, and every `await` lifted
to statement level, so a host back end is a walk over a small tree.

The crates live in this repo under `crates/` and are licensed MIT OR
Apache-2.0 like the rest of the workspace; miniscent pins them by git
revision. Moving them to a repository of their own, if a third host
appears, is open question 1.

## 3. The script profile

A host compiles with `Profile::Script`, a fixed subset of Siesta. Using
anything outside it is a load error saying "not available in scripts".

| In | Out |
|---|---|
| top-level statements, `var`s and methods; several files share one namespace | `package`, `import`; declaring `class`, `interface`, `enum` (except in interface files) |
| `boolean`, `int` (32-bit, wrapping), `double` (IEEE 754), `String`, `List<T>`, handle types from the host | `long`, `float`, `byte`, `short`, `char`, arrays, maps |
| `if`, `while`, `do`, both `for`s, arrow `switch`, `break`, `continue`, `return` | `try`, `catch`, `finally`, `throw`: an error ends the task, as miniscent fails closed |
| lambdas, capturing and changing outer variables; cascades; list literals | annotations (except name checks in interface files) |
| `async`, `await`, string interpolation, `==` and `equals`, `instanceof` and casts on handle types | generics beyond `List<T>`; anything reached through Java class files |

Semantics are Siesta's, which are Java's for arithmetic and strings: the
host's VM implements them, not its old rules. For miniscent that means
`2 + 3 * 4` is 14, lists are 0-indexed, numbers are `double` rather than
`f32` (converted at the native boundary, still checked for being finite),
and a script has `int` too.

## 4. Interface files

A host describes its API in Siesta syntax, in a `.siei` file the checker reads
like any source: interfaces for handle types and callbacks, and functions
without bodies. It is also the API reference agents read, so miniscent's
`--script-api` prints it.

```siesta
// miniscent.siei (abridged)
interface Vec {}
interface Enemy {}
interface Player { int hp(); void heal(int n); void give(String key); boolean has(String key); }
interface PlayerHandler { void run(Player p); }
interface EnemyHandler { void run(Enemy e); }
interface Condition { boolean test(); }

interface Mover {
    void pose(String pose, Vec move);
    async CompletionStage<Void> moveTo(String pose, double seconds);   // await it to wait for arrival
    boolean moving();
    void onArrived(Runnable r);
}
interface Zone {
    void onEnter(PlayerHandler h);
    void onceEnter(PlayerHandler h);
    void onExit(PlayerHandler h);
}
interface Group {
    int alive();
    void onEachDestroyed(EnemyHandler h);
    void onAllDestroyed(Runnable r);
}

Mover mover(@Name("mover") String name);
Zone zone(@Name("zone") String name);
List<Vec> markers(@Name("marker") String tagOrPrefix);
Group tagged(String tag);
Player player();
Vec xyz(double x, double y, double z);
void spawn(String kind, Vec at, String tag);
void spawnAll(String kind, List<Vec> at, String tag);
void say(String text);
async CompletionStage<Void> wait(double seconds);
async CompletionStage<Void> destroyed(Group g);
void fork(Runnable r);
```

- **Natives bind by name.** The host gives one Rust function per declared
  function and method, by qualified name and arity (`"Mover.moveTo/2"`); a declared one with no binding,
  or a binding with no declaration, fails at start-up, and a unit test
  keeps the two lists equal. The declaration is the single source of
  argument types, so the natives no longer check them.
- **Name literals are checked at load.** A parameter annotated
  `@Name("mover")` makes the checker pass every string literal given to it
  to `Universe::check_name`, which asks the level and returns the closest
  match for the message. A name built at run time is checked when the call
  runs, as today.
- **`async` natives** return to the host VM "pending" with a wake
  condition; the VM parks the task and resumes it with the result. Called
  without `await`, the native acts and the script goes on (a door starts
  moving; the script does not wait for it).

## 5. Async in a host

The rules of [`siesta-async.md`](siesta-async.md) section 2 hold, with the
host in place of executors: an `async` lambda given to a handler runs as its
own task, `await` parks that task until the host wakes it, and a failure
ends the task with file and line. In the script profile `CompletionStage<T>`
names the host's task handle, and awaiting it is the only thing a script
can do with it: `var t = destroyed(g);` then `await t;`. `Runnable` is
built in too, for handlers that take nothing. Cancellation is the host's:
miniscent ends a task when its handler is disabled.

## 6. Miniscent in Siesta

The examples of miniscent's `docs/scripting.md`, written against the
interface above:

```siesta
var vault = mover("vault_door");
vault.pose("open", xyz(0, 0, 4));

for (var m : markers("vault_switch")) {
    spawn("switch", m, "vault_switch");
}

var entry = mover("hall_entry");
var wayOut = mover("hall_exit");
var switches = tagged("vault_switch");
switches.onEachDestroyed(e -> say("${switches.alive()} left"));
switches.onAllDestroyed(() -> {
    vault.moveTo("open", 1.5);
    say("Vault unlocked");
});

zone("hall").onceEnter(async p -> {
    entry.moveTo("closed", 0.5);
    await wait(1);
    spawnAll("drone", markers("hall_wave1"), "wave1");
    await destroyed(tagged("wave1"));
    if (p.hp() < 50) p.heal(50);
    wayOut.moveTo("open", 1.5);
});
```

Library code is plain script, and other files call it unqualified:

```siesta
// assets/scripts/lib/doors.sie
void autoDoor(String name, Vec travel, String key) {
    var door = mover(name);
    door.pose("open", travel);
    var area = zone(name + "_area");
    area.onEnter(p -> {
        if (key == null || p.has(key)) door.moveTo("open", 0.6);
        else say("Needs the ${key} keycard");
    });
    area.onExit(p -> door.moveTo("closed", 0.8));
}
```

Miniscent's prelude (`waitUntil:`, `every:do:`, `after:do:`) becomes Siesta
too:
`async CompletionStage<Void> waitUntil(Condition c) { while (!c.test()) await wait(0); }`.

## 7. Untrusted input

The front end takes the host's limits as parameters (file size, nesting
depth, identifiers, constants) and reports every problem as a diagnostic
with file, line and closest match, never a panic and never unbounded
recursion: the parser counts its depth and stops at the limit. Its
`cargo fuzz` target runs `siesta-syntax` and `siesta-check` against a fixed
interface file. Run-time limits (operations, heap, tasks) stay the host
VM's.

## 8. Size

`siesta-syntax` and `siesta-check` are estimated at 6,700 of the 10,500
lines; the class-file universe and the code generator stay in `siestac`. In
a host built with `opt-level = "s"` and LTO that is an estimated 250 to
350 KB, against the 172 KB miniscent's whole script crate adds today and
its game at 1.22 MB. That is a real cost for miniscent and is measured,
not assumed, before it decides (S16-08's check). Cargo features keep the
JVM-only parts (generic method inference, overload phases) out of a script
host if the measurement says they matter.

## 9. Evidence

- **Conformance.** `tests/siesta/script/` holds programs that stay inside
  the script profile and use only `println`. Their `.out` comes from
  `siestac` on LazyVM; miniscent runs the same files through its VM with a
  stub host and must print the same, so the two back ends agree on the
  language.
- **Profile errors.** `tests/siesta/script/bad/` checks that each excluded
  feature is refused with its message.
- **Interface files.** A unit test checks a `.siei` with a stub universe:
  name literals, unknown members, a wrong handle kind and an un-annotated
  string parameter.

## 10. Open questions for the owner

1. Do the front-end crates stay in this repo, pinned by miniscent, or move
   to a repository of their own now?
2. Does miniscent adopt Siesta in place of its Smalltalk syntax? That
   reverses the decision in its `docs/scripting-language.md` and is a
   miniscent work item, measured there against its size budget.
3. Should the script profile include maps (`[k: v]`, insertion-ordered),
   which miniscent has never needed?
