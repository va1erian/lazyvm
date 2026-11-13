//! Writing a jar: a zip archive whose entries are stored, not compressed
//! (APPNOTE.TXT sections 4.3 and 4.4, the format `Jar` reads). A compiler's
//! output is small and is gzipped as a whole when packaged, so deflate would
//! buy little here for its code.

use super::{CENTRAL_SIG, EOCD_SIG, LOCAL_SIG, crc32};

/// Version 1.0 of the format is enough to extract stored entries.
const VERSION: u16 = 10;
/// Bit 11: names are UTF-8.
const UTF8_NAMES: u16 = 0x0800;
/// 1980-01-01, the format's first date, so the same input gives the same bytes.
const DATE: u16 = 0x21;

/// A jar of `entries` (name, bytes) in order, with a manifest naming
/// `main_class` (dotted) first when given. `None` when the archive would
/// exceed the format's limits without its 64-bit extension: 65,534 entries
/// (65,535 marks ZIP64), 4 GiB in all.
pub fn stored(entries: &[(String, Vec<u8>)], main_class: Option<&str>) -> Option<Vec<u8>> {
    let manifest = main_class.map(|m| {
        let text = format!("Manifest-Version: 1.0\r\nMain-Class: {m}\r\n\r\n");
        ("META-INF/MANIFEST.MF".to_string(), text.into_bytes())
    });
    let all: Vec<&(String, Vec<u8>)> = manifest.iter().chain(entries).collect();
    let count = u16::try_from(all.len()).ok().filter(|&n| n < 0xffff)?;
    let mut out = Vec::new();
    let mut central = Vec::new();
    for (name, data) in all {
        let offset = u32::try_from(out.len()).ok()?;
        let fields = Fields {
            crc: crc32(data),
            size: u32::try_from(data.len()).ok()?,
            name_len: u16::try_from(name.len()).ok()?,
        };
        put32(&mut out, LOCAL_SIG);
        fields.common(&mut out);
        out.extend_from_slice(name.as_bytes());
        out.extend_from_slice(data);

        put32(&mut central, CENTRAL_SIG);
        put16(&mut central, 20); // made by: version 2.0, MS-DOS attributes
        fields.common(&mut central);
        put16(&mut central, 0); // no comment
        put16(&mut central, 0); // disk 0
        put16(&mut central, 0); // internal attributes
        put32(&mut central, 0); // external attributes
        put32(&mut central, offset);
        central.extend_from_slice(name.as_bytes());
    }
    let central_at = u32::try_from(out.len()).ok()?;
    let central_len = u32::try_from(central.len()).ok()?;
    out.extend_from_slice(&central);
    put32(&mut out, EOCD_SIG);
    put32(&mut out, 0); // this disk, and the disk the directory starts on
    put16(&mut out, count);
    put16(&mut out, count);
    put32(&mut out, central_len);
    put32(&mut out, central_at);
    put16(&mut out, 0); // no comment
    Some(out)
}

/// The fields a local header and a central entry share, from "version
/// needed" to "extra field length".
struct Fields {
    crc: u32,
    size: u32,
    name_len: u16,
}

impl Fields {
    fn common(&self, out: &mut Vec<u8>) {
        put16(out, VERSION);
        put16(out, UTF8_NAMES);
        put16(out, 0); // stored
        put16(out, 0); // time
        put16(out, DATE);
        put32(out, self.crc);
        put32(out, self.size); // compressed
        put32(out, self.size);
        put16(out, self.name_len);
        put16(out, 0); // no extra field
    }
}

fn put16(out: &mut Vec<u8>, v: u16) {
    out.extend_from_slice(&v.to_le_bytes());
}

fn put32(out: &mut Vec<u8>, v: u32) {
    out.extend_from_slice(&v.to_le_bytes());
}
