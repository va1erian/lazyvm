# Siesta for LazyOS apps: `lazy.ui` and Messenger

Status: proposal, part of S16 in [`plan-2.md`](plan-2.md). The language is
[`siesta.md`](siesta.md), `async`/`await` is
[`siesta-async.md`](siesta-async.md), and the Messenger API this note
writes against is the [`msg.md`](msg.md) proposal. The `lazy.ui` additions
of section 3 are proposals for S9, where `lazy.ui` is being built; nothing
here changes [`ui.md`](ui.md) until the owner and that work agree.

## 1. Where app code goes today

LazyOS's Writer (`xui-app/crates/writer`, Rust on xui at `832c61f`) is
about 2,700 lines without tests. Read for this note, roughly half of it is
plumbing rather than logic:

| Plumbing | Evidence (writer `src/`) |
|---|---|
| a 44-variant `Msg` enum and a 44-arm `update` that only forwards | `app.rs:38-107`, `195-240` |
| widget handles: 20 `Handle<…>` fields, 21 `.bind`, 26 `.get()` | `ui/tools.rs:42-71` |
| dialogs built hidden, a `*Chosen` variant each, a modal flag used 29 times, a hand-made continuation for "Save changes?" | `commands/files.rs:28-107`, `182-191`, `226-235` |
| menu ids as integer constants, matched three times (build, sync, apply) | `ui/page_menu.rs:12-75` |
| toolbar dispatch by index through a parallel array of messages | `ui/mod.rs:36-50` |
| shortcuts in one global `match`, their text copied into tooltips | `app.rs:176-190`, `ui/mod.rs:55-74` |
| model copied back into widgets by hand | `ui/tools.rs:186-224` |
| printing as a timer-driven state machine | `commands/print.rs`, `ui/mod.rs:107` |

Messenger code has its own (detailed in [`msg.md`](msg.md) section 1):
hand-written encode, call and decode at every call site, a `match` on
method ids in every service, enums as `u32` constants, tick constants for
deadlines, and pull loops for callbacks and topics.

Most of this is not the language's to fix. A retained widget object that
is its own handle, and one lambda per event, already remove the `Msg` enum,
the forwarding `match` and the handle bookkeeping: that is how `lazy.ui`
is designed ([`ui.md`](ui.md)). What is left splits in two: API shapes
(section 3) and a few language features (section 2).

## 2. Language features

| Feature | Lines | Removes |
|---|---|---|
| `async`/`await`, resuming on the UI thread | (in siesta.md) | dialog continuations and modal flags, the print state machine, Messenger callbacks |
| trailing lambda: `button.onClick { save(); }` | 60 | the `(() -> { ... })` noise on every event, menu entry and subscription |
| method references `this::save` | (in siesta.md) | lambdas that only forward |
| cascade `..` | (in siesta.md) | temporary variables around setters that return `void` |

**Trailing lambda.** A call may be followed by a lambda body in braces,
which becomes its last argument; with no other argument the parentheses
may go: `save.onClick { save(); }`, `ui.menu { m -> m.item("Undo") { undo(); }; }`,
`topic.subscribe { e -> refresh(e); }`. Parameters are written
`{ a, b -> ... }`, and a leading `async` (`call() async { ... }`) makes it
an `async` lambda. Since
statements end with `;`, `call() {` can only be a trailing lambda, so the
parser needs no lookahead; after `new T(...)` it is an error that says
anonymous classes are not supported, so a Java habit gets a clear message
rather than a surprise.

**Considered and left out**, because an API does the same for less:

| Idea | Why not |
|---|---|
| properties or a `bind` keyword for model → widget sync | `Value<T>` cells in `lazy.ui` (section 3) do it with no language change |
| generating a `Msg` enum, or `@OnClick` methods | lambdas per event already remove the enum |
| `with { }` initialiser blocks, named arguments | fluent setters and cascades cover builders; named arguments need parameter names that most class files lack |
| `import "foo.midl"` compiled by `siestac` | `midlc`'s Java backend generates the same types once, for Java too ([`msg.md`](msg.md) section 3) |
| a scoped deadline block | `withTimeout(ms)` on a proxy |

So Siesta's whole addition for apps is the trailing lambda, beside what
`siesta.md` and `siesta-async.md` already define.

## 3. What `lazy.ui` should offer (proposals for S9)

Each removes one plumbing row of section 1 without language support, so
Java apps get it too:

- **Commands.** `Command saveCmd = Ui.command("Save", "Ctrl+S", Icon.SAVE) { save(); };`
  One object gives the toolbar button, the menu item, the shortcut and the
  tooltip text ("Save (Ctrl+S)"); `saveCmd.enabled(false)` greys every place it
  appears. Removes the index dispatch, the shortcut `match` and the copied
  tooltip text.
- **Menus built from lambdas**, with no ids:
  `m.radio("A4", page.paper == Paper.A4) { setPaper(Paper.A4); };`.
- **Dialogs that return stages.** `CompletionStage<Path> Ui.saveFile(name, dir)`,
  `CompletionStage<Integer> Ui.ask(text, buttons...)`,
  `CompletionStage<Void> Ui.alert(title, text)`. The runtime knows a modal
  dialog is open and refuses a second one, so the app keeps no flag.
- **Timers as stages.** `Ui.delay(ms)` completes on the UI thread, so a
  long job is a loop with `await Ui.delay(0)` between steps instead of a
  timer and a state machine.
- **Value cells.** `Value<Boolean> bold = Ui.value(false); boldButton.checked(bold);`
  A widget given a cell shows it and writes it back; `bold.set(true)` from
  the model updates every widget showing it. This replaces hand-written
  sync for the common case.
- **Groups.** `Group printBar = Ui.group(printer, copies, start)` with
  `visible` and `enabled` for all its members at once.

## 4. LazyWriter's hard parts in Siesta

Against the API of section 3 and [`msg.md`](msg.md); a sketch, not a port.

```siesta
Command newCmd = Ui.command("New", "Ctrl+N", Icon.FILE_PLUS) async { if (await confirmDiscard()) clear(); };
Command saveCmd = Ui.command("Save", "Ctrl+S", Icon.SAVE) async { await save(); };
toolbar.add(newCmd, openCmd, saveCmd, Ui.separator(), undoCmd, redoCmd);

// "Save changes?" and the save dialog: one method instead of four messages,
// a flag and a continuation field.
async CompletionStage<Boolean> confirmDiscard() {
    if (!dirty) return true;
    switch (await Ui.ask("Save changes to ${title()}?", "Save", "Don't save", "Cancel")) {
        case 0 -> { return await save(); }
        case 1 -> { return true; }
        default -> { return false; }
    }
}

async CompletionStage<Boolean> save() {
    Path target = path != null ? path : await Ui.saveFile(suggestedName(), startDir());
    if (target == null) return false;
    try {
        Files.write(target, editor.document().toRtf().getBytes(UTF_8));
        path = target;
        dirty = false;
        status.text("Saved ${target.getFileName()}");
        Trace.pass("WRITER:SAVE");
        return true;
    } catch (IOException e) {
        await Ui.alert("Could not save", "${target}: ${e.getMessage()}");
        return false;
    }
}

// Printing: a loop, not a timer, a Session struct and a tick message.
async CompletionStage<Void> print(PrintJob job) {
    var printer = Messenger.connect(Print.class);
    printBar.visible(true);
    try {
        for (int page = 0; page < job.pages() && !cancelled; page++) {
            await printer.submitPage(job.id(), render(job, page));   // a Messenger call
            progress.value(page + 1);
        }
        Messenger.connect(Notify.class).notify(new NotifyArgs().title("Printed ${job.pages()} pages"));
    } catch (MessengerException e) {
        await Ui.alert("Printing failed", e.getMessage());
    } finally {
        printBar.visible(false);
    }
}

// The page menu: no ids, no second and third match.
pageButton.onClick {
    Ui.popup(pageButton) { m ->
        m.radio("A4", page.paper == Paper.A4) { setPaper(Paper.A4); };
        m.radio("Letter", page.paper == Paper.LETTER) { setPaper(Paper.LETTER); };
        m.separator();
        m.check("Landscape", page.landscape) { on -> setLandscape(on); };
    };
};

// Following the system theme: a topic and a handler, closed with the window.
ConfdTopics.changed("sys/ui/theme").subscribe { change -> Ui.theme(change.value); };
```

A command's `async { ... }` is a fire-and-forget `async` lambda (its
interface returns `void`): a failure in it reaches the UI thread as any
callback's does.

## 5. What it should save, and how we will know

The section 1 rows are about half of Writer's non-test lines. The
`lazy.ui` API of section 3 removes them for Java as well as Siesta; Siesta
then removes the lambda and continuation noise. These are estimates from
reading the code, not measurements. Two numbers will settle it:

- the Java port of Writer onto `lazy.ui` that S9 is doing, with its line
  count, executable size and speed on LazyOS against the Rust app;
- a Siesta version of the same port (S16-08), compared line for line with
  the Java one and checked against the same screenshots.

## 6. Open questions for the owner

1. Accept the `lazy.ui` additions of section 3 into S9-05's widget coverage
   (commands, lambda menus, dialogs returning stages, `Ui.delay`, value
   cells, groups)?
2. Accept [`msg.md`](msg.md) in place of D8's annotation sketch for S10?
3. Trailing lambdas: they are the one Siesta syntax a Java reader has not
   seen. Keep them, or write `button.onClick(() -> { ... })`?
