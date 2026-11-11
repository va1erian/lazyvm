//! A small x86-64 assembler: the instructions the JIT's templates use, with
//! labels. Memory operands are always `[base + disp32]`, which keeps the
//! encoding to one form (with the SIB byte `rsp`/`r12` need).

/// General registers, numbered as the encoding numbers them.
pub(super) const RAX: u8 = 0;
pub(super) const RCX: u8 = 1;
pub(super) const RDX: u8 = 2;
pub(super) const RBX: u8 = 3;
pub(super) const RSP: u8 = 4;
pub(super) const RBP: u8 = 5;
#[cfg_attr(windows, allow(dead_code))]
pub(super) const RSI: u8 = 6;
#[cfg_attr(windows, allow(dead_code))]
pub(super) const RDI: u8 = 7;
#[cfg_attr(not(windows), allow(dead_code))]
pub(super) const R8: u8 = 8;
pub(super) const R12: u8 = 12;
pub(super) const R13: u8 = 13;
pub(super) const R14: u8 = 14;
pub(super) const R15: u8 = 15;

/// Condition codes (the low nibble of `jcc` and `setcc`).
#[derive(Clone, Copy)]
pub(super) enum Cc {
    B = 2,
    Ae = 3,
    E = 4,
    Ne = 5,
    Be = 6,
    A = 7,
    P = 0xa,
    Np = 0xb,
    L = 0xc,
    Ge = 0xd,
    Le = 0xe,
    G = 0xf,
}

/// Two-operand integer operations: the `op r, r/m` opcode and the `/digit`
/// of the `81 /digit imm32` form.
#[derive(Clone, Copy)]
pub(super) enum Alu {
    Add,
    Or,
    And,
    Sub,
    Xor,
    Cmp,
}

impl Alu {
    fn opcode(self) -> u8 {
        match self {
            Alu::Add => 0x03,
            Alu::Or => 0x0b,
            Alu::And => 0x23,
            Alu::Sub => 0x2b,
            Alu::Xor => 0x33,
            Alu::Cmp => 0x3b,
        }
    }

    fn digit(self) -> u8 {
        match self {
            Alu::Add => 0,
            Alu::Or => 1,
            Alu::And => 4,
            Alu::Sub => 5,
            Alu::Xor => 6,
            Alu::Cmp => 7,
        }
    }
}

/// A position to jump to, bound once.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub(super) struct Label(usize);

#[derive(Default)]
pub(super) struct Asm {
    pub(super) code: Vec<u8>,
    labels: Vec<Option<usize>>,
    /// (position of a rel32, label it points at)
    fixups: Vec<(usize, Label)>,
}

impl Asm {
    pub(super) fn label(&mut self) -> Label {
        self.labels.push(None);
        Label(self.labels.len() - 1)
    }

    pub(super) fn bind(&mut self, l: Label) {
        self.labels[l.0] = Some(self.code.len());
    }

    pub(super) fn pos(&self, l: Label) -> Option<usize> {
        self.labels[l.0]
    }

    /// The bytes with every jump resolved; None if a label was never bound.
    pub(super) fn finish(mut self) -> Option<Vec<u8>> {
        for &(at, l) in &self.fixups {
            let target = self.labels[l.0]? as i64;
            let rel = i32::try_from(target - (at as i64 + 4)).ok()?;
            self.code[at..at + 4].copy_from_slice(&rel.to_le_bytes());
        }
        Some(self.code)
    }

    fn byte(&mut self, b: u8) {
        self.code.push(b);
    }

    fn imm32(&mut self, v: i32) {
        self.code.extend_from_slice(&v.to_le_bytes());
    }

    /// REX for `reg` (ModRM.reg) and `rm` (ModRM.rm or the opcode register);
    /// `byte_regs` forces it so `sil`/`dil` are not `dh`/`bh` (unused here).
    fn rex(&mut self, w: bool, reg: u8, rm: u8) {
        let r = 0x40 | (u8::from(w) << 3) | ((reg >> 3) << 2) | (rm >> 3);
        if r != 0x40 {
            self.byte(r);
        }
    }

    fn modrm_mem(&mut self, reg: u8, base: u8, disp: i32) {
        self.byte(0x80 | ((reg & 7) << 3) | (base & 7));
        if base & 7 == 4 {
            self.byte(0x24);
        }
        self.imm32(disp);
    }

    fn modrm_reg(&mut self, reg: u8, rm: u8) {
        self.byte(0xc0 | ((reg & 7) << 3) | (rm & 7));
    }

    /// `[prefix] REX opcode ModRM(reg, [base+disp])`.
    fn op_mem(&mut self, prefix: Option<u8>, w: bool, op: &[u8], reg: u8, base: u8, disp: i32) {
        if let Some(p) = prefix {
            self.byte(p);
        }
        self.rex(w, reg, base);
        self.code.extend_from_slice(op);
        self.modrm_mem(reg, base, disp);
    }

    /// `[prefix] REX opcode ModRM(reg, rm)`.
    fn op_reg(&mut self, prefix: Option<u8>, w: bool, op: &[u8], reg: u8, rm: u8) {
        if let Some(p) = prefix {
            self.byte(p);
        }
        self.rex(w, reg, rm);
        self.code.extend_from_slice(op);
        self.modrm_reg(reg, rm);
    }

    // ---- moves ----

    pub(super) fn load(&mut self, w: bool, r: u8, base: u8, disp: i32) {
        self.op_mem(None, w, &[0x8b], r, base, disp);
    }

    pub(super) fn store(&mut self, w: bool, base: u8, disp: i32, r: u8) {
        self.op_mem(None, w, &[0x89], r, base, disp);
    }

    /// `mov dword/qword [base+disp], imm32` (sign-extended for a qword).
    pub(super) fn store_imm(&mut self, w: bool, base: u8, disp: i32, imm: i32) {
        self.op_mem(None, w, &[0xc7], 0, base, disp);
        self.imm32(imm);
    }

    pub(super) fn mov_imm64(&mut self, r: u8, imm: u64) {
        self.rex(true, 0, r);
        self.byte(0xb8 + (r & 7));
        self.code.extend_from_slice(&imm.to_le_bytes());
    }

    /// `lea r64, [base+disp]`.
    pub(super) fn lea(&mut self, r: u8, base: u8, disp: i32) {
        self.op_mem(None, true, &[0x8d], r, base, disp);
    }

    pub(super) fn mov_rr(&mut self, w: bool, dst: u8, src: u8) {
        self.op_reg(None, w, &[0x89], src, dst);
    }

    /// `movsxd r64, dword [base+disp]`.
    pub(super) fn load_sx32(&mut self, r: u8, base: u8, disp: i32) {
        self.op_mem(None, true, &[0x63], r, base, disp);
    }

    /// `movsx`/`movzx r32, r8/r16` for `i2b`, `i2c`, `i2s`; `op` is the second
    /// opcode byte (0xbe, 0xb7, 0xbf, 0xb6). `src` is rax, rcx or rdx.
    pub(super) fn extend(&mut self, op: u8, dst: u8, src: u8) {
        self.op_reg(None, false, &[0x0f, op], dst, src);
    }

    // ---- integer arithmetic ----

    pub(super) fn alu_mem(&mut self, op: Alu, w: bool, r: u8, base: u8, disp: i32) {
        self.op_mem(None, w, &[op.opcode()], r, base, disp);
    }

    pub(super) fn alu_rr(&mut self, op: Alu, w: bool, dst: u8, src: u8) {
        self.op_reg(None, w, &[op.opcode()], dst, src);
    }

    pub(super) fn alu_mem_imm(&mut self, op: Alu, w: bool, base: u8, disp: i32, imm: i32) {
        self.op_mem(None, w, &[0x81], op.digit(), base, disp);
        self.imm32(imm);
    }

    pub(super) fn alu_reg_imm(&mut self, op: Alu, w: bool, r: u8, imm: i32) {
        self.op_reg(None, w, &[0x81], op.digit(), r);
        self.imm32(imm);
    }

    /// `cmp byte [base+disp], imm8`.
    pub(super) fn cmp_byte(&mut self, base: u8, disp: i32, imm: u8) {
        self.op_mem(None, false, &[0x80], 7, base, disp);
        self.byte(imm);
    }

    pub(super) fn imul_mem(&mut self, w: bool, r: u8, base: u8, disp: i32) {
        self.op_mem(None, w, &[0x0f, 0xaf], r, base, disp);
    }

    pub(super) fn neg_mem(&mut self, w: bool, base: u8, disp: i32) {
        self.op_mem(None, w, &[0xf7], 3, base, disp);
    }

    /// `shl`/`shr`/`sar r, cl`: `digit` 4, 5 or 7. The CPU masks the count to
    /// 5 or 6 bits, as Java does.
    pub(super) fn shift_cl(&mut self, w: bool, digit: u8, r: u8) {
        self.op_reg(None, w, &[0xd3], digit, r);
    }

    /// `cdq` (or `cqo`), then `idiv r`.
    pub(super) fn idiv(&mut self, w: bool, r: u8) {
        if w {
            self.byte(0x48);
        }
        self.byte(0x99);
        self.op_reg(None, w, &[0xf7], 7, r);
    }

    /// `setcc r8` then `movzx r32, r8`, for rax, rcx or rdx.
    pub(super) fn setcc(&mut self, cc: Cc, r: u8) {
        self.op_reg(None, false, &[0x0f, 0x90 | cc as u8], 0, r);
        self.op_reg(None, false, &[0x0f, 0xb6], r, r);
    }

    /// `btc qword [base+disp], bit`: flips a `double`'s sign.
    pub(super) fn btc_mem(&mut self, base: u8, disp: i32, bit: u8) {
        self.op_mem(None, true, &[0x0f, 0xba], 7, base, disp);
        self.byte(bit);
    }

    // ---- SSE ----

    /// Scalar `op` (0x10 load, 0x58 add, 0x59 mul, 0x5c sub, 0x5e div, 0x5a
    /// convert to the other width) on `xmm` and `[base+disp]`; `double` picks
    /// F2 over F3.
    pub(super) fn sse_mem(&mut self, double: bool, op: u8, x: u8, base: u8, disp: i32) {
        let p = if double { 0xf2 } else { 0xf3 };
        self.op_mem(Some(p), false, &[0x0f, op], x, base, disp);
    }

    /// `movss`/`movsd [base+disp], xmm`.
    pub(super) fn sse_store(&mut self, double: bool, base: u8, disp: i32, x: u8) {
        let p = if double { 0xf2 } else { 0xf3 };
        self.op_mem(Some(p), false, &[0x0f, 0x11], x, base, disp);
    }

    /// `ucomiss`/`ucomisd a, b`.
    pub(super) fn ucomi(&mut self, double: bool, a: u8, b: u8) {
        let p = double.then_some(0x66);
        self.op_reg(p, false, &[0x0f, 0x2e], a, b);
    }

    /// `cvtsi2ss`/`cvtsi2sd xmm, dword/qword [base+disp]`.
    pub(super) fn cvt_from_int(&mut self, double: bool, long: bool, x: u8, base: u8, disp: i32) {
        let p = if double { 0xf2 } else { 0xf3 };
        self.op_mem(Some(p), long, &[0x0f, 0x2a], x, base, disp);
    }

    /// `cvttss2si`/`cvttsd2si r32/r64, xmm`.
    pub(super) fn cvt_to_int(&mut self, double: bool, long: bool, r: u8, x: u8) {
        let p = if double { 0xf2 } else { 0xf3 };
        self.op_reg(Some(p), long, &[0x0f, 0x2c], r, x);
    }

    /// `movups xmm, [base+disp]` / `movups [base+disp], xmm`: one whole Value.
    pub(super) fn movups_load(&mut self, x: u8, base: u8, disp: i32) {
        self.op_mem(None, false, &[0x0f, 0x10], x, base, disp);
    }

    pub(super) fn movups_store(&mut self, base: u8, disp: i32, x: u8) {
        self.op_mem(None, false, &[0x0f, 0x11], x, base, disp);
    }

    // ---- control ----

    pub(super) fn jmp(&mut self, l: Label) {
        self.byte(0xe9);
        self.fixups.push((self.code.len(), l));
        self.imm32(0);
    }

    pub(super) fn jcc(&mut self, cc: Cc, l: Label) {
        self.byte(0x0f);
        self.byte(0x80 | cc as u8);
        self.fixups.push((self.code.len(), l));
        self.imm32(0);
    }

    pub(super) fn jmp_reg(&mut self, r: u8) {
        self.op_reg(None, false, &[0xff], 4, r);
    }

    pub(super) fn call_reg(&mut self, r: u8) {
        self.op_reg(None, false, &[0xff], 2, r);
    }

    pub(super) fn push(&mut self, r: u8) {
        self.rex(false, 0, r);
        self.byte(0x50 + (r & 7));
    }

    pub(super) fn pop(&mut self, r: u8) {
        self.rex(false, 0, r);
        self.byte(0x58 + (r & 7));
    }

    pub(super) fn ret(&mut self) {
        self.byte(0xc3);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bytes(f: impl FnOnce(&mut Asm)) -> Vec<u8> {
        let mut a = Asm::default();
        f(&mut a);
        a.finish().unwrap_or_default()
    }

    // Expected bytes from the Intel SDM encodings, checked with llvm-objdump.
    #[test]
    fn encodings() {
        assert_eq!(
            bytes(|a| a.load(false, RAX, R12, 0x10)),
            [0x41, 0x8b, 0x84, 0x24, 0x10, 0, 0, 0]
        );
        assert_eq!(
            bytes(|a| a.store(true, R13, 0x20, RAX)),
            [0x49, 0x89, 0x85, 0x20, 0, 0, 0]
        );
        assert_eq!(
            bytes(|a| a.sse_mem(false, 0x58, 0, RBX, 4)),
            [0xf3, 0x0f, 0x58, 0x83, 4, 0, 0, 0]
        );
        assert_eq!(
            bytes(|a| a.mov_imm64(R8, 1)),
            [0x49, 0xb8, 1, 0, 0, 0, 0, 0, 0, 0]
        );
        assert_eq!(bytes(|a| a.alu_rr(Alu::Add, false, RAX, RCX)), [0x03, 0xc1]);
        assert_eq!(bytes(|a| a.push(R12)), [0x41, 0x54]);
        assert_eq!(bytes(|a| a.idiv(true, RCX)), [0x48, 0x99, 0x48, 0xf7, 0xf9]);
        assert_eq!(
            bytes(|a| a.setcc(Cc::L, RAX)),
            [0x0f, 0x9c, 0xc0, 0x0f, 0xb6, 0xc0]
        );
    }

    #[test]
    fn jumps_resolve() {
        let code = bytes(|a| {
            let l = a.label();
            a.jmp(l);
            a.ret();
            a.bind(l);
        });
        assert_eq!(code, [0xe9, 1, 0, 0, 0, 0xc3]);
    }
}
