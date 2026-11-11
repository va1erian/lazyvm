//! Arithmetic, logic, shifts, conversions and compares (0x60-0x98), with the
//! semantics of `ops_math`: integer arithmetic wraps, `x / -1` and `x % -1`
//! never trap (x86's `idiv` would on `MIN / -1`), shift counts are masked
//! (as the CPU does), float to integer conversion saturates and maps NaN to
//! zero, `frem`/`drem` and a zero integer divisor leave for the interpreter.
//! The result goes in the lowest operand's entry; its tag is written when
//! the kind changes.

use super::asm::{Alu, Cc, R13, RAX, RCX, RDX};
use super::frame::{AT, Gen, s};
use super::layout::{DOUBLE, FLOAT, HIGH, INT, LONG, LOW, TAG};

fn tag(g: &mut Gen, at: usize, t: u32) {
    g.a.store_imm(false, R13, s(at) + TAG, t as i32);
}

/// Integer `idiv`/`irem`/`ldiv`/`lrem` on entries a (dividend) and b.
fn divide(g: &mut Gen, long: bool, rem: bool, pc: usize, d: usize) {
    let (a, b) = (d - 2, d - 1);
    let at = if long { HIGH } else { LOW };
    let zero = g.exit_stub(pc, d, AT);
    let minus_one = g.a.label();
    let done = g.a.label();
    g.a.load(long, RCX, R13, s(b) + at);
    g.a.alu_reg_imm(Alu::Cmp, long, RCX, 0);
    g.a.jcc(Cc::E, zero);
    g.a.alu_reg_imm(Alu::Cmp, long, RCX, -1);
    g.a.jcc(Cc::E, minus_one);
    g.a.load(long, RAX, R13, s(a) + at);
    g.a.idiv(long, RCX);
    g.a.store(long, R13, s(a) + at, if rem { RDX } else { RAX });
    g.a.jmp(done);
    g.a.bind(minus_one);
    if rem {
        g.a.store_imm(long, R13, s(a) + at, 0);
    } else {
        g.a.neg_mem(long, R13, s(a) + at);
    }
    g.a.bind(done);
}

/// 0x60-0x83. False for `frem`/`drem`, left to the interpreter.
pub(super) fn emit(g: &mut Gen, op: u8, pc: usize, sh: &[bool]) -> bool {
    let d = sh.len();
    if (0x74..=0x77).contains(&op) {
        let x = d - 1;
        match op {
            0x74 => g.a.neg_mem(false, R13, s(x) + LOW),
            0x75 => g.a.neg_mem(true, R13, s(x) + HIGH),
            0x76 => g.a.alu_mem_imm(Alu::Xor, false, R13, s(x) + LOW, i32::MIN),
            _ => g.a.btc_mem(R13, s(x) + HIGH, 63),
        }
        return true;
    }
    let (a, b) = (d - 2, d - 1);
    if op >= 0x78 {
        let long = op % 2 == 1;
        let at = if long { HIGH } else { LOW };
        g.a.load(long, RAX, R13, s(a) + at);
        match op {
            0x78..=0x7d => {
                g.a.load(false, RCX, R13, s(b) + LOW);
                let digit = match op {
                    0x78 | 0x79 => 4,
                    0x7a | 0x7b => 7,
                    _ => 5,
                };
                g.a.shift_cl(long, digit, RAX);
            }
            _ => {
                let alu = match op {
                    0x7e | 0x7f => Alu::And,
                    0x80 | 0x81 => Alu::Or,
                    _ => Alu::Xor,
                };
                g.a.alu_mem(alu, long, RAX, R13, s(b) + at);
            }
        }
        g.a.store(long, R13, s(a) + at, RAX);
        return true;
    }
    let kind = (op - 0x60) % 4;
    let operation = (op - 0x60) / 4;
    match (kind, operation) {
        (0 | 1, 3 | 4) => divide(g, kind == 1, operation == 4, pc, d),
        (0 | 1, _) => {
            let long = kind == 1;
            let at = if long { HIGH } else { LOW };
            g.a.load(long, RAX, R13, s(a) + at);
            match operation {
                0 => g.a.alu_mem(Alu::Add, long, RAX, R13, s(b) + at),
                1 => g.a.alu_mem(Alu::Sub, long, RAX, R13, s(b) + at),
                _ => g.a.imul_mem(long, RAX, R13, s(b) + at),
            }
            g.a.store(long, R13, s(a) + at, RAX);
        }
        (_, 4) => return false,
        _ => {
            let double = kind == 3;
            let at = if double { HIGH } else { LOW };
            let sse = [0x58, 0x5c, 0x59, 0x5e][usize::from(operation)];
            g.a.sse_mem(double, 0x10, 0, R13, s(a) + at);
            g.a.sse_mem(double, sse, 0, R13, s(b) + at);
            g.a.sse_store(double, R13, s(a) + at, 0);
        }
    }
    true
}

/// A float or double in xmm0 to int or long in rax, the Java way: NaN gives
/// 0, out of range saturates (the CPU gives MIN for both).
fn to_int(g: &mut Gen, double: bool, long: bool, x: usize, at: i32) {
    let done = g.a.label();
    let nan = g.a.label();
    g.a.cvt_to_int(double, long, RAX, 0);
    if long {
        g.a.mov_imm64(RDX, i64::MIN as u64);
        g.a.alu_rr(Alu::Cmp, true, RAX, RDX);
    } else {
        g.a.alu_reg_imm(Alu::Cmp, false, RAX, i32::MIN);
    }
    g.a.jcc(Cc::Ne, done);
    g.a.ucomi(double, 0, 0);
    g.a.jcc(Cc::P, nan);
    // Not NaN: a non-negative input saturates up; a negative one is MIN.
    g.a.load(double, RCX, R13, s(x) + at);
    g.a.alu_reg_imm(Alu::Cmp, double, RCX, 0);
    g.a.jcc(Cc::L, done);
    g.a.mov_imm64(
        RAX,
        if long {
            i64::MAX as u64
        } else {
            i32::MAX as u64
        },
    );
    g.a.jmp(done);
    g.a.bind(nan);
    g.a.alu_rr(Alu::Xor, false, RAX, RAX);
    g.a.bind(done);
}

/// 0x85-0x93, on the top entry in place.
pub(super) fn convert(g: &mut Gen, op: u8, d: usize) {
    let x = d - 1;
    match op {
        0x85 => {
            g.a.load_sx32(RAX, R13, s(x) + LOW);
            g.a.store(true, R13, s(x) + HIGH, RAX);
            tag(g, x, LONG);
        }
        0x88 => {
            g.a.load(false, RAX, R13, s(x) + HIGH);
            g.a.store(false, R13, s(x) + LOW, RAX);
            tag(g, x, INT);
        }
        0x86 | 0x87 | 0x89 | 0x8a => {
            let long = op >= 0x89;
            let double = op == 0x87 || op == 0x8a;
            g.a.cvt_from_int(double, long, 0, R13, s(x) + if long { HIGH } else { LOW });
            g.a.sse_store(double, R13, s(x) + if double { HIGH } else { LOW }, 0);
            tag(g, x, if double { DOUBLE } else { FLOAT });
        }
        0x8d | 0x90 => {
            let from_double = op == 0x90;
            let (from, to) = if from_double {
                (HIGH, LOW)
            } else {
                (LOW, HIGH)
            };
            g.a.sse_mem(from_double, 0x5a, 0, R13, s(x) + from);
            g.a.sse_store(!from_double, R13, s(x) + to, 0);
            tag(g, x, if from_double { FLOAT } else { DOUBLE });
        }
        0x8b | 0x8c | 0x8e | 0x8f => {
            let double = op >= 0x8e;
            let long = op == 0x8c || op == 0x8f;
            let at = if double { HIGH } else { LOW };
            g.a.sse_mem(double, 0x10, 0, R13, s(x) + at);
            to_int(g, double, long, x, at);
            g.a.store(long, R13, s(x) + if long { HIGH } else { LOW }, RAX);
            tag(g, x, if long { LONG } else { INT });
        }
        _ => {
            g.a.load(false, RAX, R13, s(x) + LOW);
            let ext = match op {
                0x91 => 0xbe,
                0x92 => 0xb7,
                _ => 0xbf,
            };
            g.a.extend(ext, RAX, RAX);
            g.a.store(false, R13, s(x) + LOW, RAX);
        }
    }
}

/// 0x94-0x98: -1, 0 or 1 in the lower entry; NaN gives -1 (`l`) or 1 (`g`).
pub(super) fn compare(g: &mut Gen, op: u8, d: usize) {
    let (a, b) = (d - 2, d - 1);
    let done = g.a.label();
    if op == 0x94 {
        g.a.load(true, RAX, R13, s(a) + HIGH);
        g.a.alu_mem(Alu::Cmp, true, RAX, R13, s(b) + HIGH);
        g.a.setcc(Cc::G, RCX);
        g.a.setcc(Cc::L, RDX);
    } else {
        let double = op >= 0x97;
        let at = if double { HIGH } else { LOW };
        let nan = g.a.label();
        g.a.sse_mem(double, 0x10, 0, R13, s(a) + at);
        g.a.sse_mem(double, 0x10, 1, R13, s(b) + at);
        g.a.ucomi(double, 0, 1);
        g.a.jcc(Cc::P, nan);
        g.a.setcc(Cc::A, RCX);
        g.a.setcc(Cc::B, RDX);
        g.a.alu_rr(Alu::Sub, false, RCX, RDX);
        g.a.jmp(done);
        g.a.bind(nan);
        let v = if op == 0x95 || op == 0x97 { -1 } else { 1 };
        g.a.mov_imm64(RCX, v as i64 as u64);
        g.a.store(false, R13, s(a) + LOW, RCX);
        tag(g, a, INT);
        let end = g.a.label();
        g.a.jmp(end);
        g.a.bind(done);
        g.a.store(false, R13, s(a) + LOW, RCX);
        tag(g, a, INT);
        g.a.bind(end);
        return;
    }
    g.a.alu_rr(Alu::Sub, false, RCX, RDX);
    g.a.bind(done);
    g.a.store(false, R13, s(a) + LOW, RCX);
    tag(g, a, INT);
}
