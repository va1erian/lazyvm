//! The attributes the VM interprets (JVMS 4.7). Everything else is kept raw.

use std::sync::Arc;

use crate::FormatError;
use crate::cpool::ConstantPool;
use crate::reader::Reader;

/// An attribute kept as bytes for a later stage (annotations, `Signature`, ...).
#[derive(Clone, Debug)]
pub struct RawAttribute {
    pub name: Arc<str>,
    pub data: Arc<[u8]>,
}

/// One exception table entry (JVMS 4.7.3). `catch_type` 0 catches everything.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Handler {
    pub start_pc: u16,
    pub end_pc: u16,
    pub handler_pc: u16,
    pub catch_type: u16,
}

#[derive(Clone, Debug)]
pub struct Code {
    pub max_stack: u16,
    pub max_locals: u16,
    pub bytecode: Arc<[u8]>,
    pub handlers: Vec<Handler>,
    /// `(start_pc, line)` pairs from every `LineNumberTable`, sorted by pc.
    pub line_numbers: Vec<(u16, u16)>,
    /// The raw `StackMapTable`, read by the verifier.
    pub stack_map: Option<Arc<[u8]>>,
}

impl Code {
    /// The source line of `pc`, if the class has line numbers.
    pub fn line_at(&self, pc: usize) -> Option<u16> {
        let idx = self
            .line_numbers
            .partition_point(|&(start, _)| usize::from(start) <= pc);
        idx.checked_sub(1).map(|i| self.line_numbers[i].1)
    }
}

/// One `BootstrapMethods` entry (JVMS 4.7.23).
#[derive(Clone, Debug)]
pub struct BootstrapMethod {
    pub method_ref: u16,
    pub arguments: Vec<u16>,
}

pub fn parse_code(data: &[u8], pool: &ConstantPool, major: u16) -> Result<Code, FormatError> {
    let mut r = Reader::new(data);
    let max_stack = r.u2()?;
    let max_locals = r.u2()?;
    let len = r.u4()? as usize;
    if len == 0 || len >= 65536 {
        return Err(r.error(format!("code_length {len} out of range")));
    }
    let bytecode: Arc<[u8]> = r.bytes(len)?.into();
    let count = r.u2()?;
    let mut handlers = Vec::with_capacity(usize::from(count));
    for _ in 0..count {
        let h = Handler {
            start_pc: r.u2()?,
            end_pc: r.u2()?,
            handler_pc: r.u2()?,
            catch_type: r.u2()?,
        };
        let ok = h.start_pc < h.end_pc
            && usize::from(h.end_pc) <= len
            && usize::from(h.handler_pc) < len;
        if !ok {
            return Err(r.error(format!("bad exception handler {h:?}")));
        }
        if h.catch_type != 0 {
            pool.class_name(h.catch_type).map_err(|e| r.error(e))?;
        }
        handlers.push(h);
    }
    let mut code = Code {
        max_stack,
        max_locals,
        bytecode,
        handlers,
        line_numbers: Vec::new(),
        stack_map: None,
    };
    let attrs = r.u2()?;
    for _ in 0..attrs {
        let name = pool.utf8(r.u2()?).map_err(|e| r.error(e))?.clone();
        let body = r.sized()?;
        match &*name {
            "LineNumberTable" => parse_lines(body, &mut code.line_numbers)?,
            // The attribute means nothing before version 50 (JVMS 4.7.4).
            "StackMapTable" if major < 50 => {}
            "StackMapTable" if code.stack_map.is_some() => {
                return Err(r.error("two StackMapTable attributes"));
            }
            "StackMapTable" => code.stack_map = Some(body.into()),
            _ => {}
        }
    }
    r.expect_end("Code")?;
    code.line_numbers.sort_unstable();
    Ok(code)
}

fn parse_lines(data: &[u8], out: &mut Vec<(u16, u16)>) -> Result<(), FormatError> {
    let mut r = Reader::new(data);
    let count = r.u2()?;
    for _ in 0..count {
        out.push((r.u2()?, r.u2()?));
    }
    r.expect_end("LineNumberTable")
}

pub fn parse_bootstrap_methods(data: &[u8]) -> Result<Vec<BootstrapMethod>, FormatError> {
    let mut r = Reader::new(data);
    let count = r.u2()?;
    let mut out = Vec::with_capacity(usize::from(count));
    for _ in 0..count {
        let method_ref = r.u2()?;
        let n = r.u2()?;
        let mut arguments = Vec::with_capacity(usize::from(n));
        for _ in 0..n {
            arguments.push(r.u2()?);
        }
        out.push(BootstrapMethod {
            method_ref,
            arguments,
        });
    }
    r.expect_end("BootstrapMethods")?;
    Ok(out)
}

/// A u2 index attribute body (`ConstantValue`, `SourceFile`, `Signature`).
pub fn parse_index(data: &[u8], what: &str) -> Result<u16, FormatError> {
    let mut r = Reader::new(data);
    let index = r.u2()?;
    r.expect_end(what)?;
    Ok(index)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn line_at_picks_the_last_entry_at_or_before_pc() {
        let code = Code {
            max_stack: 0,
            max_locals: 0,
            bytecode: Arc::from(&[0u8][..]),
            handlers: vec![],
            line_numbers: vec![(0, 10), (4, 11), (9, 12)],
            stack_map: None,
        };
        assert_eq!(code.line_at(0), Some(10));
        assert_eq!(code.line_at(5), Some(11));
        assert_eq!(code.line_at(100), Some(12));
    }
}
