//! `pop` to `swap`: stack entries move as whole Values. The shape before the
//! instruction says which entries are wide, so each form is a fixed set of
//! moves (JVMS 6.5, as `ops_basic::stack_op` and `flow::shuffle`).

use super::asm::R13;
use super::frame::{Gen, s};

fn mv(g: &mut Gen, from: usize, to: usize, x: u8) {
    g.a.movups_load(x, R13, s(from));
    g.a.movups_store(R13, s(to), x);
}

/// True if emitted; the forms `flow` accepted always are.
pub(super) fn shuffle(g: &mut Gen, op: u8, sh: &[bool]) -> bool {
    let d = sh.len();
    let wide = |depth: usize| sh[d - 1 - depth];
    // (copy, under): the top `copy` entries are copied below `under` more.
    let (copy, under) = match op {
        0x57 | 0x58 => return true, // the shape drops them
        0x59 => (1, 0),
        0x5a => (1, 1),
        0x5b => (1, if wide(1) { 1 } else { 2 }),
        0x5c => (if wide(0) { 1 } else { 2 }, 0),
        0x5d => (if wide(0) { 1 } else { 2 }, 1),
        0x5e => {
            let copy = if wide(0) { 1 } else { 2 };
            (copy, if wide(copy) { 1 } else { 2 })
        }
        0x5f => {
            // Through two registers: entry d may be past the stack's room.
            g.a.movups_load(0, R13, s(d - 1));
            g.a.movups_load(1, R13, s(d - 2));
            g.a.movups_store(R13, s(d - 2), 0);
            g.a.movups_store(R13, s(d - 1), 1);
            return true;
        }
        _ => return false,
    };
    // [.. u c] -> [.. c u c]: entries start..d move up by `copy`, top
    // first (c lands at d..d+copy), then c is copied down to start.
    let start = d - copy - under;
    for i in (start..d).rev() {
        mv(g, i, i + copy, 0);
    }
    for k in 0..copy {
        mv(g, d + k, start + k, 0);
    }
    true
}
