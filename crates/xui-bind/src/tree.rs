//! Decoding the op list Java encodes a widget tree into (docs/ui.md,
//! "Building"). Every count, index and size is checked: a malformed list is
//! an error, never a panic.
//!
//! The list is the tree in pre-order. Each node starts with its opcode and
//! the id Java gave it:
//! - `COLUMN`/`ROW id padding gap n`, then its `n` children;
//! - `LABEL id text`, where `text` indexes the string table;
//! - `BUTTON id text click`, where `click` is 1 if Java listens for clicks.

use xui_core::arrange::{Entry, Handle, IntoEntry, Layout, button, column, label, row};

use crate::app::Msg;
use crate::table::{self, Widget};

pub mod op {
    pub const COLUMN: i32 = 1;
    pub const ROW: i32 = 2;
    pub const LABEL: i32 = 3;
    pub const BUTTON: i32 = 4;
    /// The event kind of a click, as `lazy.ui.Ui.dispatch` receives it.
    pub const CLICK: i32 = 1;
}

/// xui forms nest at most 16 deep; Java trees follow the same bound.
const MAX_DEPTH: usize = 16;
/// The largest padding or gap, in design units (xui's `MAX_LENGTH`).
const MAX_LENGTH: i32 = 32768;

/// A window's widgets, as Java encoded them.
pub struct Tree {
    pub ops: Vec<i32>,
    pub strings: Vec<String>,
}

struct Reader<'a> {
    tree: &'a Tree,
    pos: usize,
}

impl Reader<'_> {
    fn int(&mut self) -> Result<i32, String> {
        let v = self.tree.ops.get(self.pos).copied();
        self.pos += 1;
        v.ok_or_else(|| "op list ends early".to_string())
    }

    fn length(&mut self) -> Result<i32, String> {
        let v = self.int()?;
        if (0..=MAX_LENGTH).contains(&v) {
            Ok(v)
        } else {
            Err(format!("length {v} out of range"))
        }
    }

    fn string(&mut self) -> Result<String, String> {
        let i = self.int()?;
        usize::try_from(i)
            .ok()
            .and_then(|i| self.tree.strings.get(i))
            .cloned()
            .ok_or_else(|| format!("string index {i} out of range"))
    }

    fn layout(&mut self, depth: usize) -> Result<Layout<Msg>, String> {
        if depth > MAX_DEPTH {
            return Err("tree nests too deep".into());
        }
        let kind = self.int()?;
        let _id = self.int()?;
        let mut layout = match kind {
            op::COLUMN => column(),
            op::ROW => row(),
            _ => return Err(format!("op {kind} is not a layout")),
        };
        layout = layout.padding(self.length()?).gap(self.length()?);
        let n = self.int()?;
        // Each child takes at least two ints, so a larger count is malformed.
        let left = self.tree.ops.len().saturating_sub(self.pos) / 2;
        let n = usize::try_from(n).ok().filter(|&n| n <= left);
        let n = n.ok_or_else(|| "bad child count".to_string())?;
        let mut children = Vec::with_capacity(n);
        for _ in 0..n {
            children.push(self.entry(depth + 1)?);
        }
        Ok(layout.children(children))
    }

    fn entry(&mut self, depth: usize) -> Result<Entry<Msg>, String> {
        let kind = *self.tree.ops.get(self.pos).ok_or("op list ends early")?;
        if kind == op::COLUMN || kind == op::ROW {
            return Ok(self.layout(depth)?.into_entry());
        }
        self.pos += 1;
        let id = self.int()?;
        match kind {
            op::LABEL => {
                let handle = Handle::new();
                table::insert(id, Widget::Label(handle.clone()))?;
                Ok(label(self.string()?).bind(&handle).into_entry())
            }
            op::BUTTON => {
                let handle = Handle::new();
                table::insert(id, Widget::Button(handle.clone()))?;
                let mut b = button(self.string()?).bind(&handle);
                if self.int()? == 1 {
                    table::listen(id);
                    b = b.on_click(Msg {
                        id,
                        kind: op::CLICK,
                    });
                }
                Ok(b.into_entry())
            }
            _ => Err(format!("unknown op {kind}")),
        }
    }
}

/// The root layout of `tree`; the list must hold exactly one tree.
pub(crate) fn decode(tree: &Tree) -> Result<Layout<Msg>, String> {
    let mut r = Reader { tree, pos: 0 };
    let root = r.layout(1)?;
    if r.pos != tree.ops.len() {
        return Err("op list has trailing ops".into());
    }
    Ok(root)
}
