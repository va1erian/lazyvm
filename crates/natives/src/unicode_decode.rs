//! Decoder and validator for `data/unicode.bin` (format: `unicode.rs`).
//!
//! Every length and value read from the data is checked, so a damaged file
//! yields an error string and never a panic or an unbounded allocation.

use crate::unicode::{Entry, Multi, Runs, Sparse, Tables};

const LIMIT: u64 = 0x11_0000;
const VERSION: u16 = 1;

pub(crate) fn fnv32(data: &[u8]) -> u32 {
    data.iter().fold(0x811C_9DC5u32, |h, &b| {
        (h ^ u32::from(b)).wrapping_mul(0x0100_0193)
    })
}

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl Reader<'_> {
    fn byte(&mut self) -> Result<u8, String> {
        let b = *self.data.get(self.pos).ok_or("truncated")?;
        self.pos += 1;
        Ok(b)
    }

    fn varint(&mut self) -> Result<u64, String> {
        let mut n = 0u64;
        for shift in (0..70).step_by(7) {
            let b = self.byte()?;
            if shift == 63 && b > 1 {
                return Err("varint overflows 64 bits".into());
            }
            n |= u64::from(b & 0x7F) << shift;
            if b & 0x80 == 0 {
                return Ok(n);
            }
        }
        Err("varint too long".into())
    }

    /// A varint that must be at most the size of the code space.
    fn small(&mut self) -> Result<u64, String> {
        let n = self.varint()?;
        if n > LIMIT {
            return Err("value beyond the code space".into());
        }
        Ok(n)
    }

    fn zigzag(&mut self) -> Result<i32, String> {
        let n = self.varint()?;
        let v = (n >> 1) as i64 ^ -((n & 1) as i64);
        i32::try_from(v).map_err(|_| "value out of range".into())
    }

    /// An element count, bounded by the bytes left (each element takes at least one).
    fn count(&mut self) -> Result<usize, String> {
        let n = self.varint()?;
        match usize::try_from(n) {
            Ok(n) if n <= self.data.len() - self.pos => Ok(n),
            _ => Err("count exceeds the data".into()),
        }
    }
}

fn runs(r: &mut Reader, max_value: u8) -> Result<Runs, String> {
    let n = r.count()?;
    let (mut starts, mut values) = (Vec::with_capacity(n), Vec::with_capacity(n));
    let mut at = 0u64;
    for _ in 0..n {
        let len = r.small()?;
        let value = u8::try_from(r.varint()?).ok().filter(|&v| v <= max_value);
        if len == 0 || len > LIMIT - at {
            return Err("run length out of range".into());
        }
        starts.push(at as u32);
        values.push(value.ok_or("run value out of range")?);
        at += len;
    }
    if at != LIMIT {
        return Err("run table does not cover every code point".into());
    }
    Ok(Runs { starts, values })
}

/// One entry, and the end of its last code point (exclusive).
fn entry(r: &mut Reader, prev_end: u64) -> Result<(Entry, u64), String> {
    let start = prev_end + r.small()?;
    let count = r.small()? + 1;
    let stride = if count > 1 { r.small()? + 1 } else { 1 };
    let first = r.zigzag()?;
    let step = if count > 1 { r.zigzag()? } else { 0 };
    let last = start + (count - 1) * stride;
    if last >= LIMIT {
        return Err("sparse entry beyond the code space".into());
    }
    let entry = Entry {
        start: start as u32,
        count: count as u32,
        stride: stride as u32,
        first,
        step,
    };
    Ok((entry, last + 1))
}

/// The first and last values of an entry must fit, which bounds every one between.
fn check_values(e: &Entry, relative: bool) -> Result<(), String> {
    let last_index = i64::from(e.count - 1);
    let ends = [
        (i64::from(e.first), i64::from(e.start)),
        (
            i64::from(e.first) + last_index * i64::from(e.step),
            i64::from(e.start) + last_index * i64::from(e.stride),
        ),
    ];
    for (value, cp) in ends {
        let v = if relative { value + cp } else { value };
        let ok = if relative {
            (0..LIMIT as i64).contains(&v)
        } else {
            i32::try_from(v).is_ok()
        };
        if !ok {
            return Err("value out of range".into());
        }
    }
    Ok(())
}

fn sparse(r: &mut Reader, relative: bool) -> Result<Sparse, String> {
    let n = r.count()?;
    let mut entries = Vec::with_capacity(n);
    let mut end = 0u64;
    for _ in 0..n {
        let (e, next) = entry(r, end)?;
        check_values(&e, relative)?;
        entries.push(e);
        end = next;
    }
    Ok(Sparse { entries, relative })
}

fn multi(r: &mut Reader) -> Result<Vec<Multi>, String> {
    let n = r.count()?;
    let mut out: Vec<Multi> = Vec::with_capacity(n);
    let mut prev = 0u64;
    for i in 0..n {
        let cp = prev + r.small()?;
        let len = r.small()? as usize;
        if cp >= LIMIT || (i > 0 && cp == prev) || !(2..=3).contains(&len) {
            return Err("bad special mapping".into());
        }
        let mut chars = [0u32; 3];
        for slot in chars.iter_mut().take(len) {
            *slot = u32::try_from(r.small()?)
                .ok()
                .filter(|&c| u64::from(c) < LIMIT)
                .ok_or("special mapping beyond the code space")?;
        }
        out.push(Multi {
            cp: cp as u32,
            len,
            chars,
        });
        prev = cp;
    }
    Ok(out)
}

pub(crate) fn parse(data: &[u8]) -> Result<Tables, String> {
    let body_len = data.len().checked_sub(4).ok_or("too short")?;
    let (body, sum) = data.split_at(body_len);
    if sum != fnv32(body).to_le_bytes() {
        return Err("checksum mismatch".into());
    }
    let [m0, m1, m2, m3, v0, v1, ..] = *body else {
        return Err("too short".into());
    };
    if [m0, m1, m2, m3] != *b"LZUC" {
        return Err("bad magic".into());
    }
    if u16::from_le_bytes([v0, v1]) != VERSION {
        return Err("unsupported version".into());
    }
    let mut r = Reader { data: body, pos: 6 };
    let tables = Tables {
        category: runs(&mut r, 30)?,
        direction: runs(&mut r, 19)?,
        flags: runs(&mut r, 255)?,
        upper: sparse(&mut r, true)?,
        lower: sparse(&mut r, true)?,
        title: sparse(&mut r, true)?,
        digit: sparse(&mut r, false)?,
        numeric: sparse(&mut r, false)?,
        special_upper: multi(&mut r)?,
        special_lower: multi(&mut r)?,
    };
    if r.pos != body.len() {
        return Err("trailing bytes".into());
    }
    Ok(tables)
}
