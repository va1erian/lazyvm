//! Reading instructions from a `code` array (JVMS 6.5). Every read is checked:
//! the structural pass runs this over untrusted bytes.

pub const OP_GOTO: u8 = 0xa7;
pub const OP_JSR: u8 = 0xa8;
pub const OP_RET: u8 = 0xa9;
pub const OP_TABLESWITCH: u8 = 0xaa;
pub const OP_LOOKUPSWITCH: u8 = 0xab;
pub const OP_NEW: u8 = 0xbb;
pub const OP_WIDE: u8 = 0xc4;
pub const OP_GOTO_W: u8 = 0xc8;
pub const OP_JSR_W: u8 = 0xc9;

pub fn u1(code: &[u8], at: usize) -> Result<u8, String> {
    code.get(at)
        .copied()
        .ok_or_else(|| "instruction runs past the end of the code".to_string())
}

pub fn u2(code: &[u8], at: usize) -> Result<u16, String> {
    Ok(u16::from_be_bytes([u1(code, at)?, u1(code, at + 1)?]))
}

pub fn i2(code: &[u8], at: usize) -> Result<i16, String> {
    Ok(u2(code, at)? as i16)
}

pub fn i4(code: &[u8], at: usize) -> Result<i32, String> {
    Ok(i32::from_be_bytes([
        u1(code, at)?,
        u1(code, at + 1)?,
        u1(code, at + 2)?,
        u1(code, at + 3)?,
    ]))
}

/// A `tableswitch` or `lookupswitch`: where its operands end, its targets
/// (the default first) as absolute offsets, and whether its matches are sorted.
pub struct Switch {
    pub len: usize,
    pub targets: Vec<i64>,
    pub sorted: bool,
}

/// The padding brings the first operand to a multiple of four from the start
/// of the code (JVMS 6.5 tableswitch).
pub fn switch(code: &[u8], pc: usize) -> Result<Switch, String> {
    let base = (pc + 4) & !3;
    let target = |at: usize| Ok::<i64, String>(pc as i64 + i64::from(i4(code, at)?));
    let mut targets = vec![target(base)?];
    if u1(code, pc)? == OP_TABLESWITCH {
        let (low, high) = (i4(code, base + 4)?, i4(code, base + 8)?);
        if low > high {
            return Err("tableswitch low is above high".into());
        }
        let count = (i64::from(high) - i64::from(low) + 1) as usize;
        let end = count
            .checked_mul(4)
            .and_then(|n| n.checked_add(base + 12))
            .filter(|&e| e <= code.len())
            .ok_or("tableswitch runs past the end of the code")?;
        for at in (base + 12..end).step_by(4) {
            targets.push(target(at)?);
        }
        return Ok(Switch {
            len: end - pc,
            targets,
            sorted: true,
        });
    }
    let npairs = i4(code, base + 4)?;
    let count = usize::try_from(npairs).map_err(|_| "lookupswitch npairs is negative")?;
    let end = count
        .checked_mul(8)
        .and_then(|n| n.checked_add(base + 8))
        .filter(|&e| e <= code.len())
        .ok_or("lookupswitch runs past the end of the code")?;
    let mut sorted = true;
    let mut last = None;
    for at in (base + 8..end).step_by(8) {
        let key = i4(code, at)?;
        sorted &= last.is_none_or(|l| l < key);
        last = Some(key);
        targets.push(target(at + 4)?);
    }
    Ok(Switch {
        len: end - pc,
        targets,
        sorted,
    })
}

/// The length of the instruction at `pc`, operands included.
pub fn length(code: &[u8], pc: usize) -> Result<usize, String> {
    let op = u1(code, pc)?;
    let len = match op {
        0x00..=0x0f | 0x1a..=0x35 | 0x3b..=0x83 | 0x85..=0x98 | 0xac..=0xb1 | 0xbe | 0xbf => 1,
        0xc2 | 0xc3 => 1,
        0x10 | 0x12 | 0x15..=0x19 | 0x36..=0x3a | OP_RET | 0xbc => 2,
        0x11 | 0x13 | 0x14 | 0x84 | 0x99..=OP_JSR | 0xb2..=0xb8 | OP_NEW | 0xbd => 3,
        0xc0 | 0xc1 | 0xc6 | 0xc7 => 3,
        0xc5 => 4,
        0xb9 | 0xba | OP_GOTO_W | OP_JSR_W => 5,
        OP_TABLESWITCH | OP_LOOKUPSWITCH => switch(code, pc)?.len,
        OP_WIDE => {
            if u1(code, pc + 1)? == 0x84 {
                6
            } else {
                4
            }
        }
        _ => return Err(format!("illegal opcode {op:#04x}")),
    };
    Ok(len)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn switch_padding_follows_the_code_start() {
        // At pc 1 the operands start at 4: default +20, low 0, high 0, one target.
        let mut code = vec![0, OP_TABLESWITCH, 0, 0];
        code.extend_from_slice(&20i32.to_be_bytes());
        code.extend_from_slice(&0i32.to_be_bytes());
        code.extend_from_slice(&0i32.to_be_bytes());
        code.extend_from_slice(&(-1i32).to_be_bytes());
        let sw = switch(&code, 1).unwrap_or_else(|e| panic!("{e}"));
        assert_eq!(sw.len, 19);
        assert_eq!(sw.targets, [21, 0]);
    }

    #[test]
    fn huge_switch_counts_do_not_overflow() {
        let mut code = vec![OP_TABLESWITCH, 0, 0, 0];
        code.extend_from_slice(&0i32.to_be_bytes());
        code.extend_from_slice(&i32::MIN.to_be_bytes());
        code.extend_from_slice(&i32::MAX.to_be_bytes());
        assert!(switch(&code, 0).is_err());
        let mut code = vec![OP_LOOKUPSWITCH, 0, 0, 0];
        code.extend_from_slice(&0i32.to_be_bytes());
        code.extend_from_slice(&i32::MAX.to_be_bytes());
        assert!(switch(&code, 0).is_err());
    }
}
