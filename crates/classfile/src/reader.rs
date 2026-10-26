//! A bounds-checked big-endian cursor over class file bytes.

use crate::FormatError;

/// Reads big-endian values; every read checks the remaining length, so a
/// truncated or hostile file becomes a `FormatError`, never a panic.
pub struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub fn new(data: &'a [u8]) -> Self {
        Reader { data, pos: 0 }
    }

    pub fn pos(&self) -> usize {
        self.pos
    }

    pub fn remaining(&self) -> usize {
        self.data.len() - self.pos
    }

    pub fn error(&self, msg: impl Into<String>) -> FormatError {
        FormatError::new(self.pos, msg)
    }

    pub fn bytes(&mut self, n: usize) -> Result<&'a [u8], FormatError> {
        if n > self.remaining() {
            return Err(self.error(format!(
                "truncated: need {n} bytes, {} left",
                self.remaining()
            )));
        }
        let out = &self.data[self.pos..self.pos + n];
        self.pos += n;
        Ok(out)
    }

    pub fn u1(&mut self) -> Result<u8, FormatError> {
        Ok(self.bytes(1)?[0])
    }

    pub fn u2(&mut self) -> Result<u16, FormatError> {
        let b = self.bytes(2)?;
        Ok(u16::from_be_bytes([b[0], b[1]]))
    }

    pub fn u4(&mut self) -> Result<u32, FormatError> {
        let b = self.bytes(4)?;
        Ok(u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }

    pub fn u8(&mut self) -> Result<u64, FormatError> {
        let hi = u64::from(self.u4()?);
        let lo = u64::from(self.u4()?);
        Ok((hi << 32) | lo)
    }

    /// A u4 length followed by that many bytes, as an attribute body is laid out.
    pub fn sized(&mut self) -> Result<&'a [u8], FormatError> {
        let len = self.u4()? as usize;
        self.bytes(len)
    }

    pub fn expect_end(&self, what: &str) -> Result<(), FormatError> {
        if self.remaining() != 0 {
            return Err(self.error(format!("{} extra bytes after {what}", self.remaining())));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_big_endian_and_rejects_truncation() {
        let mut r = Reader::new(&[0x12, 0x34, 0x56, 0x78, 0x9a]);
        assert_eq!(r.u2().ok(), Some(0x1234));
        assert_eq!(r.u2().ok(), Some(0x5678));
        assert!(r.u2().is_err());
        assert_eq!(r.u1().ok(), Some(0x9a));
        assert!(r.u1().is_err());
    }

    #[test]
    fn sized_checks_the_declared_length() {
        let mut r = Reader::new(&[0, 0, 0, 9, 1, 2]);
        assert!(r.sized().is_err());
    }
}
