//! The JDWP packet layer: headers, a bounded reader and a writer.
//!
//! Nothing here trusts the wire. Every length is checked against the bytes
//! that are really there before anything is allocated or sliced, and a failed
//! check is a JDWP error code, never a panic.

use std::io::{self, Read};

/// JDWP error codes (the ones this agent returns).
pub mod err {
    pub const INVALID_THREAD: u16 = 10;
    pub const THREAD_NOT_ALIVE: u16 = 15;
    pub const THREAD_NOT_SUSPENDED: u16 = 13;
    pub const INVALID_OBJECT: u16 = 20;
    pub const INVALID_CLASS: u16 = 21;
    pub const INVALID_METHODID: u16 = 23;
    pub const INVALID_LOCATION: u16 = 24;
    pub const INVALID_FIELDID: u16 = 25;
    pub const INVALID_FRAMEID: u16 = 30;
    pub const INVALID_SLOT: u16 = 35;
    pub const TYPE_MISMATCH: u16 = 34;
    pub const DUPLICATE: u16 = 40;
    pub const NOT_IMPLEMENTED: u16 = 99;
    pub const ABSENT_INFORMATION: u16 = 101;
    pub const INVALID_EVENT_TYPE: u16 = 102;
    pub const ILLEGAL_ARGUMENT: u16 = 103;
    pub const OUT_OF_MEMORY: u16 = 110;
    pub const INTERNAL: u16 = 113;
    pub const INVALID_INDEX: u16 = 503;
    pub const INVALID_LENGTH: u16 = 504;
    pub const INVALID_COUNT: u16 = 512;
    pub const INVALID_ARRAY: u16 = 508;
    pub const INVALID_STRING: u16 = 506;
}

pub const HANDSHAKE: &[u8] = b"JDWP-Handshake";
pub const HEADER_LEN: usize = 11;
/// The largest packet read. The commands this agent takes are a few hundred
/// bytes; a debugger sending more than this is not one.
pub const MAX_PACKET: usize = 1 << 20;
const REPLY_FLAG: u8 = 0x80;

/// A command packet, header split off.
#[derive(Debug, PartialEq, Eq)]
pub struct Command {
    pub id: u32,
    pub set: u8,
    pub cmd: u8,
    pub data: Vec<u8>,
}

/// Why a packet could not be read: the connection is unusable after any of these.
#[derive(Debug)]
pub enum PacketError {
    Io(io::Error),
    /// The length field is below the header size or above [`MAX_PACKET`].
    BadLength(u32),
    /// A reply where a command was expected.
    NotACommand,
}

/// Reads one command packet. The length is checked before the body is allocated.
pub fn read_command(from: &mut impl Read) -> Result<Command, PacketError> {
    let mut head = [0u8; HEADER_LEN];
    from.read_exact(&mut head).map_err(PacketError::Io)?;
    let length = u32::from_be_bytes([head[0], head[1], head[2], head[3]]);
    let total = usize::try_from(length).unwrap_or(usize::MAX);
    if !(HEADER_LEN..=MAX_PACKET).contains(&total) {
        return Err(PacketError::BadLength(length));
    }
    let mut data = vec![0u8; total - HEADER_LEN];
    from.read_exact(&mut data).map_err(PacketError::Io)?;
    if head[8] & REPLY_FLAG != 0 {
        return Err(PacketError::NotACommand);
    }
    Ok(Command {
        id: u32::from_be_bytes([head[4], head[5], head[6], head[7]]),
        set: head[9],
        cmd: head[10],
        data,
    })
}

/// A reply packet: the id of the command, an error code (0 for none) and its data.
pub fn reply_packet(id: u32, error: u16, data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(HEADER_LEN + data.len());
    out.extend_from_slice(&((HEADER_LEN + data.len()) as u32).to_be_bytes());
    out.extend_from_slice(&id.to_be_bytes());
    out.push(REPLY_FLAG);
    out.extend_from_slice(&error.to_be_bytes());
    out.extend_from_slice(data);
    out
}

/// A command packet the VM sends (an event: set 64, command 100).
pub fn command_packet(id: u32, set: u8, cmd: u8, data: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(HEADER_LEN + data.len());
    out.extend_from_slice(&((HEADER_LEN + data.len()) as u32).to_be_bytes());
    out.extend_from_slice(&id.to_be_bytes());
    out.extend_from_slice(&[0, set, cmd]);
    out.extend_from_slice(data);
    out
}

/// Reads the fields of a command's data, in order.
pub struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
}

pub type Res<T> = Result<T, u16>;

impl<'a> Reader<'a> {
    pub fn new(data: &'a [u8]) -> Reader<'a> {
        Reader { data, pos: 0 }
    }

    pub fn remaining(&self) -> usize {
        self.data.len() - self.pos
    }

    fn take(&mut self, n: usize) -> Res<&'a [u8]> {
        if n > self.remaining() {
            return Err(err::ILLEGAL_ARGUMENT);
        }
        let out = &self.data[self.pos..self.pos + n];
        self.pos += n;
        Ok(out)
    }

    pub fn u8(&mut self) -> Res<u8> {
        Ok(self.take(1)?[0])
    }

    pub fn i32(&mut self) -> Res<i32> {
        let b = self.take(4)?;
        Ok(i32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    }

    pub fn i64(&mut self) -> Res<i64> {
        let b = self.take(8)?;
        Ok(i64::from_be_bytes([
            b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
        ]))
    }

    /// Any id: all of them are eight bytes (`IDSizes`).
    pub fn id(&mut self) -> Res<u64> {
        self.i64().map(|v| v as u64)
    }

    /// A count of items that each take at least `min_item` bytes, which must
    /// fit in what is left, so a huge count cannot make the caller allocate.
    pub fn count(&mut self, min_item: usize) -> Res<usize> {
        let n = usize::try_from(self.i32()?).map_err(|_| err::ILLEGAL_ARGUMENT)?;
        match n.checked_mul(min_item.max(1)) {
            Some(bytes) if bytes <= self.remaining() => Ok(n),
            _ => Err(err::ILLEGAL_ARGUMENT),
        }
    }

    /// A length-prefixed string. JDWP says UTF-8; bytes that are not valid
    /// are `INVALID_STRING`.
    pub fn string(&mut self) -> Res<String> {
        let n = usize::try_from(self.i32()?).map_err(|_| err::INVALID_LENGTH)?;
        let bytes = self.take(n).map_err(|_| err::INVALID_LENGTH)?;
        String::from_utf8(bytes.to_vec()).map_err(|_| err::INVALID_STRING)
    }
}

/// Builds a reply's data.
#[derive(Default)]
pub struct Writer {
    pub buf: Vec<u8>,
}

impl Writer {
    pub fn u8(&mut self, v: u8) {
        self.buf.push(v);
    }

    pub fn bool(&mut self, v: bool) {
        self.buf.push(u8::from(v));
    }

    pub fn u16(&mut self, v: u16) {
        self.buf.extend_from_slice(&v.to_be_bytes());
    }

    pub fn i32(&mut self, v: i32) {
        self.buf.extend_from_slice(&v.to_be_bytes());
    }

    pub fn i64(&mut self, v: i64) {
        self.buf.extend_from_slice(&v.to_be_bytes());
    }

    pub fn id(&mut self, v: u64) {
        self.buf.extend_from_slice(&v.to_be_bytes());
    }

    pub fn len(&mut self, n: usize) {
        self.i32(i32::try_from(n).unwrap_or(i32::MAX));
    }

    pub fn string(&mut self, s: &str) {
        self.len(s.len());
        self.buf.extend_from_slice(s.as_bytes());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn packet(length: u32, flags: u8, body: &[u8]) -> Vec<u8> {
        let mut p = length.to_be_bytes().to_vec();
        p.extend_from_slice(&[0, 0, 0, 7, flags, 1, 2]);
        p.extend_from_slice(body);
        p
    }

    #[test]
    fn a_well_formed_command_is_read() {
        let bytes = packet(13, 0, &[9, 8]);
        let c = read_command(&mut &bytes[..]).expect("test input");
        assert_eq!((c.id, c.set, c.cmd, c.data), (7, 1, 2, vec![9, 8]));
    }

    #[test]
    fn lengths_outside_the_limits_are_refused_before_any_allocation() {
        for length in [0, 10, MAX_PACKET as u32 + 1, u32::MAX] {
            let bytes = packet(length, 0, &[]);
            assert!(matches!(
                read_command(&mut &bytes[..]),
                Err(PacketError::BadLength(l)) if l == length
            ));
        }
    }

    #[test]
    fn a_body_shorter_than_its_length_is_an_io_error() {
        let bytes = packet(100, 0, &[1, 2, 3]);
        assert!(matches!(
            read_command(&mut &bytes[..]),
            Err(PacketError::Io(_))
        ));
        let bytes = [0u8; 5];
        assert!(matches!(
            read_command(&mut &bytes[..]),
            Err(PacketError::Io(_))
        ));
    }

    #[test]
    fn a_reply_is_not_a_command() {
        let bytes = packet(11, 0x80, &[]);
        assert!(matches!(
            read_command(&mut &bytes[..]),
            Err(PacketError::NotACommand)
        ));
    }

    #[test]
    fn the_reader_stops_at_the_end_of_the_data() {
        let mut r = Reader::new(&[0, 0, 0, 5, 1]);
        assert_eq!(r.i32(), Ok(5));
        assert_eq!(r.u8(), Ok(1));
        assert_eq!(r.u8(), Err(err::ILLEGAL_ARGUMENT));
        assert_eq!(Reader::new(&[0, 0, 1]).i32(), Err(err::ILLEGAL_ARGUMENT));
        assert_eq!(
            Reader::new(&[1, 2, 3, 4, 5, 6, 7]).id(),
            Err(err::ILLEGAL_ARGUMENT)
        );
    }

    #[test]
    fn a_count_that_does_not_fit_the_data_is_refused() {
        let huge = [0x7f, 0xff, 0xff, 0xff, 1, 2, 3];
        assert_eq!(Reader::new(&huge).count(8), Err(err::ILLEGAL_ARGUMENT));
        assert_eq!(
            Reader::new(&[0xff, 0xff, 0xff, 0xff]).count(1),
            Err(err::ILLEGAL_ARGUMENT)
        );
        let ok = [0, 0, 0, 2, 1, 2];
        assert_eq!(Reader::new(&ok).count(1), Ok(2));
    }

    #[test]
    fn strings_are_bounded_and_must_be_utf8() {
        let mut data = 1_000_000i32.to_be_bytes().to_vec();
        data.extend_from_slice(b"abc");
        assert_eq!(Reader::new(&data).string(), Err(err::INVALID_LENGTH));
        assert_eq!(
            Reader::new(&[0xff, 0xff, 0xff, 0xff]).string(),
            Err(err::INVALID_LENGTH)
        );
        let mut bad = 2i32.to_be_bytes().to_vec();
        bad.extend_from_slice(&[0xc3, 0x28]);
        assert_eq!(Reader::new(&bad).string(), Err(err::INVALID_STRING));
        let mut good = 3i32.to_be_bytes().to_vec();
        good.extend_from_slice("é!".as_bytes());
        assert_eq!(Reader::new(&good).string().as_deref(), Ok("é!"));
    }

    #[test]
    fn a_reply_has_the_reply_flag_and_the_error_in_the_place_of_the_command() {
        let p = reply_packet(3, 99, &[1]);
        assert_eq!(p, [0, 0, 0, 12, 0, 0, 0, 3, 0x80, 0, 99, 1]);
    }
}
