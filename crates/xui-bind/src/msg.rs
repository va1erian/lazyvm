//! The one message type every `lazy.ui` widget raises, and the event kinds
//! `lazy.ui.Ui.dispatch` receives (docs/ui.md, "How it runs").

/// What happened to a Java object, as `Ui.dispatch(id, kind, i, d, s)` sees
/// it. The payload slots mean what the kind says; unused ones are zero.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Msg {
    /// The Java registry id: a slot and a generation (docs/ui.md, "Identity").
    pub id: i32,
    pub kind: i32,
    pub i: i32,
    pub d: f64,
    pub s: Option<String>,
}

impl Msg {
    pub fn new(id: i32, kind: i32) -> Msg {
        Msg {
            id,
            kind,
            ..Msg::default()
        }
    }

    pub fn int(id: i32, kind: i32, i: i32) -> Msg {
        Msg {
            id,
            kind,
            i,
            ..Msg::default()
        }
    }

    pub fn text(id: i32, kind: i32, s: String) -> Msg {
        Msg {
            id,
            kind,
            s: Some(s),
            ..Msg::default()
        }
    }
}

/// The event kinds, shared with `lazy.ui.Ui`'s constants.
pub mod kind {
    /// A click on a button, a toolbar item (`i`: the item) or a menu entry.
    pub const CLICK: i32 = 1;
    /// A toggle or check box changed: `i` is 1 when now on. Menus: `i` the
    /// entry, `d` 1.0 when now checked.
    pub const TOGGLE: i32 = 2;
    /// A selection: `i` is the index, -1 for none.
    pub const SELECT: i32 = 3;
    /// Text changed: `s` is the new text (rich text: `i` is the word count).
    pub const CHANGE: i32 = 4;
    /// A list row was activated (double click, Enter): `i` is the row.
    pub const ACTIVATE: i32 = 5;
    /// A slider moved: `d` is the value.
    pub const SLIDE: i32 = 6;
    /// A timer fired.
    pub const TIMER: i32 = 7;
    /// The window's close button.
    pub const CLOSE: i32 = 8;
    /// A window shortcut: `i` is its index.
    pub const KEY: i32 = 9;
    /// A dialog ended: `i` is the command (0 accept, 1.. task commands),
    /// -1 cancel; `s` the prompt's text or the chosen path.
    pub const DIALOG: i32 = 10;
    /// Rich text: the selection's style changed (`i` packed, `d` the size,
    /// `s` the family).
    pub const STYLE: i32 = 11;
    /// Rich text: a link was Ctrl+clicked; `s` is its address.
    pub const LINK: i32 = 12;
    /// `Ui.executor()` has work queued (docs/ui.md, "Threads").
    pub const WAKE: i32 = 13;
}
