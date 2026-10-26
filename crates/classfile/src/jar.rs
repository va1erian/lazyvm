//! Jar files: the zip central directory (APPNOTE 4.3), stored and deflated
//! entries, and CRC checks. Hostile archives are errors, never panics:
//! offsets and sizes are checked, inflation is capped at the declared size,
//! and ZIP64, encryption and multi-disk archives are refused.

use std::collections::HashMap;
use std::sync::Arc;

use crate::FormatError;
use crate::reader::Reader;

const EOCD_SIG: u32 = 0x0605_4b50;
const CENTRAL_SIG: u32 = 0x0201_4b50;
const LOCAL_SIG: u32 = 0x0403_4b50;
const EOCD_LEN: usize = 22;
/// No single entry may inflate beyond this, whatever its header claims.
pub const MAX_ENTRY: u64 = 256 << 20;

#[derive(Clone, Copy, Debug)]
struct Entry {
    local_offset: u32,
    compressed: u32,
    size: u32,
    method: u16,
    crc: u32,
}

#[derive(Clone)]
pub struct Jar {
    data: Arc<[u8]>,
    entries: HashMap<String, Entry>,
    /// Entry names in central directory order.
    order: Vec<String>,
}

impl std::fmt::Debug for Jar {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Jar({} entries)", self.order.len())
    }
}

impl Jar {
    pub fn open(data: impl Into<Arc<[u8]>>) -> Result<Jar, FormatError> {
        let data: Arc<[u8]> = data.into();
        let eocd = find_eocd(&data)?;
        let e = &data[eocd..eocd + EOCD_LEN];
        let (disk, cd_disk, count) = (le16(&e[4..]), le16(&e[6..]), le16(&e[10..]));
        let (cd_size, cd_offset) = (le32(&e[12..]) as usize, le32(&e[16..]) as usize);
        if disk != 0 || cd_disk != 0 {
            return Err(FormatError::new(eocd, "multi-disk zip archives are not supported"));
        }
        if count == 0xffff || cd_offset == 0xffff_ffff {
            return Err(FormatError::new(eocd, "ZIP64 archives are not supported"));
        }
        let cd = data
            .get(cd_offset..cd_offset.saturating_add(cd_size))
            .ok_or_else(|| FormatError::new(eocd, "central directory outside the file"))?;
        let mut jar = Jar { data: data.clone(), entries: HashMap::new(), order: Vec::new() };
        let mut r = Reader::new(cd);
        for _ in 0..count {
            let (name, entry) = parse_central(&mut r)?;
            if !jar.entries.contains_key(&name) {
                jar.order.push(name.clone());
                jar.entries.insert(name, entry);
            }
        }
        Ok(jar)
    }

    pub fn names(&self) -> &[String] {
        &self.order
    }

    pub fn contains(&self, name: &str) -> bool {
        self.entries.contains_key(name)
    }

    /// The bytes of entry `name`, or `None` if there is no such entry.
    pub fn read(&self, name: &str) -> Result<Option<Vec<u8>>, FormatError> {
        let Some(&e) = self.entries.get(name) else { return Ok(None) };
        let at = e.local_offset as usize;
        let outside = || FormatError::new(at, format!("{name}: entry outside the file"));
        let header = self.data.get(at..at.saturating_add(30)).ok_or_else(outside)?;
        if le32(header) != LOCAL_SIG {
            return Err(FormatError::new(at, format!("bad local header for {name}")));
        }
        let start = at + 30 + usize::from(le16(&header[26..])) + usize::from(le16(&header[28..]));
        let raw = self
            .data
            .get(start..start.saturating_add(e.compressed as usize))
            .ok_or_else(outside)?;
        let out = match e.method {
            0 => raw.to_vec(),
            8 => miniz_oxide::inflate::decompress_to_vec_with_limit(raw, e.size as usize)
                .map_err(|_| FormatError::new(at, format!("corrupt deflate data in {name}")))?,
            m => return Err(FormatError::new(at, format!("{name}: unsupported compression method {m}"))),
        };
        if out.len() != e.size as usize || crc32(&out) != e.crc {
            return Err(FormatError::new(at, format!("{name}: size or CRC mismatch")));
        }
        Ok(Some(out))
    }
}

fn find_eocd(data: &[u8]) -> Result<usize, FormatError> {
    if data.len() < EOCD_LEN {
        return Err(FormatError::new(0, "not a zip archive (too short)"));
    }
    // The record sits at the end, followed by a comment of at most 65535 bytes.
    let lowest = data.len().saturating_sub(EOCD_LEN + 0xffff);
    (lowest..=data.len() - EOCD_LEN)
        .rev()
        .find(|&i| data[i..i + 4] == EOCD_SIG.to_le_bytes())
        .ok_or_else(|| FormatError::new(0, "not a zip archive (no end of central directory)"))
}

fn le16(b: &[u8]) -> u16 {
    u16::from_le_bytes([b[0], b[1]])
}

fn le32(b: &[u8]) -> u32 {
    u32::from_le_bytes([b[0], b[1], b[2], b[3]])
}

/// One central directory record. Zip is little-endian, so fields are read
/// from a fixed 46-byte slice rather than through the big-endian `Reader`.
fn parse_central(r: &mut Reader<'_>) -> Result<(String, Entry), FormatError> {
    let at = r.pos();
    let h = r.bytes(46)?;
    if le32(h) != CENTRAL_SIG {
        return Err(FormatError::new(at, "bad central directory record"));
    }
    let flags = le16(&h[8..]);
    if flags & 1 != 0 {
        return Err(FormatError::new(at, "encrypted zip entries are not supported"));
    }
    let entry = Entry {
        method: le16(&h[10..]),
        crc: le32(&h[16..]),
        compressed: le32(&h[20..]),
        size: le32(&h[24..]),
        local_offset: le32(&h[42..]),
    };
    if u64::from(entry.size) > MAX_ENTRY || entry.size == u32::MAX || entry.compressed == u32::MAX {
        return Err(FormatError::new(at, "zip entry too large"));
    }
    let (name_len, extra_len, comment_len) = (usize::from(le16(&h[28..])), usize::from(le16(&h[30..])), usize::from(le16(&h[32..])));
    let name = String::from_utf8(r.bytes(name_len)?.to_vec()).map_err(|_| FormatError::new(at, "zip entry name is not UTF-8"))?;
    r.bytes(extra_len + comment_len)?;
    Ok((name, entry))
}

/// CRC-32 (IEEE 802.3, reflected), as zip uses.
pub fn crc32(data: &[u8]) -> u32 {
    static TABLE: std::sync::OnceLock<[u32; 256]> = std::sync::OnceLock::new();
    let table = TABLE.get_or_init(|| {
        let mut t = [0u32; 256];
        for (i, slot) in t.iter_mut().enumerate() {
            let mut c = i as u32;
            for _ in 0..8 {
                c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
            }
            *slot = c;
        }
        t
    });
    !data.iter().fold(!0u32, |c, &b| table[((c ^ u32::from(b)) & 0xff) as usize] ^ (c >> 8))
}

/// The main section of a `META-INF/MANIFEST.MF` (JAR spec): `Name: value`
/// lines, continuation lines starting with one space.
pub fn manifest_attributes(text: &str) -> Vec<(String, String)> {
    let mut out: Vec<(String, String)> = Vec::new();
    for line in text.split('\n').map(|l| l.strip_suffix('\r').unwrap_or(l)) {
        if line.is_empty() {
            break;
        }
        if let Some(cont) = line.strip_prefix(' ') {
            if let Some(last) = out.last_mut() {
                last.1.push_str(cont);
            }
        } else if let Some((k, v)) = line.split_once(": ") {
            out.push((k.to_owned(), v.to_owned()));
        }
    }
    out
}

#[cfg(test)]
mod tests;
