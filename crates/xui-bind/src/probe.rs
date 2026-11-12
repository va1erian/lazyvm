//! A picture's size from its header, read before anything is decoded, so a
//! small file that claims a huge picture is refused without allocating it.

fn be16(b: &[u8], at: usize) -> Option<u32> {
    Some(u32::from(*b.get(at)?) << 8 | u32::from(*b.get(at + 1)?))
}

fn be32(b: &[u8], at: usize) -> Option<u32> {
    Some(be16(b, at)? << 16 | be16(b, at + 2)?)
}

/// PNG: the signature, then the IHDR chunk with width and height.
fn png(b: &[u8]) -> Option<(u32, u32)> {
    const SIGNATURE: [u8; 8] = [0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a];
    (b.get(..8)? == SIGNATURE && b.get(12..16)? == b"IHDR").then_some(())?;
    Some((be32(b, 16)?, be32(b, 20)?))
}

/// JPEG: walks the marker segments to the first start-of-frame.
fn jpeg(b: &[u8]) -> Option<(u32, u32)> {
    (b.get(..2)? == [0xff, 0xd8]).then_some(())?;
    let mut at = 2;
    loop {
        if *b.get(at)? != 0xff {
            return None;
        }
        let marker = *b.get(at + 1)?;
        if marker == 0xff {
            at += 1; // fill byte
            continue;
        }
        let len = be16(b, at + 2)? as usize;
        // SOF0 to SOF15, except DHT (C4), JPG (C8) and DAC (CC).
        if (0xc0..=0xcf).contains(&marker) && ![0xc4, 0xc8, 0xcc].contains(&marker) {
            return Some((be16(b, at + 7)?, be16(b, at + 5)?));
        }
        if len < 2 {
            return None;
        }
        at = at.checked_add(2 + len)?;
    }
}

/// The width and height a PNG or JPEG file declares, or `None` for
/// anything else or a truncated header.
pub fn dimensions(bytes: &[u8]) -> Option<(u32, u32)> {
    png(bytes).or_else(|| jpeg(bytes))
}

#[cfg(test)]
mod tests {
    use super::dimensions;

    #[test]
    fn a_png_header_gives_its_size() {
        let mut b = vec![0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13];
        b.extend_from_slice(b"IHDR");
        b.extend_from_slice(&[0, 0, 1, 0, 0, 0, 0, 3]);
        assert_eq!(dimensions(&b), Some((256, 3)));
        assert_eq!(dimensions(&b[..20]), None);
    }

    #[test]
    fn a_jpeg_header_gives_its_size() {
        // SOI, an APP0 segment of 4 bytes, then SOF0: precision, height 2, width 513.
        let b = [
            0xff, 0xd8, 0xff, 0xe0, 0, 4, 0, 0, 0xff, 0xc0, 0, 17, 8, 0, 2, 2, 1,
        ];
        assert_eq!(dimensions(&b), Some((513, 2)));
    }

    #[test]
    fn garbage_and_loops_give_none() {
        assert_eq!(dimensions(b"not a picture"), None);
        assert_eq!(dimensions(&[0xff, 0xd8, 0xff, 0xe0, 0, 0]), None);
        assert_eq!(dimensions(&[0xff, 0xd8, 0xff, 0xe0, 0, 2, 0xff]), None);
        assert_eq!(dimensions(&[]), None);
    }
}
