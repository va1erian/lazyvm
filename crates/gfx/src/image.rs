//! PNG decoding for `lazy.gfx.Image`. Input is untrusted: every failure is an
//! error string. The png crate's `Limits` do not cover its row buffers or
//! ours, so memory is bounded here from the header, before any pixel buffer
//! exists: by the pixel count and by the raw (undecoded) row length.

use std::io::Cursor;
use std::panic::{AssertUnwindSafe, catch_unwind};

use png::{ColorType, Decoder, Limits, Reader, Transformations};

/// The largest image, as 0xAARRGGBB pixels (4 bytes each), that is decoded.
const MAX_BYTES: usize = 64 << 20;
/// The longest raw row: the decoder keeps a few rows of this size. 1 MiB
/// still allows 131072 pixels of 16-bit RGBA.
const MAX_ROW_BYTES: u64 = 1 << 20;

/// Reads the header, refusing what the decoder could not handle within bounds.
fn reader(data: &[u8]) -> Result<Reader<Cursor<&[u8]>>, String> {
    let mut decoder = Decoder::new_with_limits(Cursor::new(data), Limits { bytes: MAX_BYTES });
    decoder.set_transformations(Transformations::normalize_to_color8());
    // Unused metadata: an iCCP profile is otherwise inflated, up to the
    // Limits, before any of our checks run.
    decoder.set_ignore_iccp_chunk(true);
    decoder.set_ignore_text_chunk(true);
    let reader = decoder.read_info().map_err(|e| e.to_string())?;
    let info = reader.info();
    let (w, h) = info.size();
    let pixels_ok = (w as usize)
        .checked_mul(h as usize)
        .and_then(|n| n.checked_mul(4))
        .is_some_and(|n| n <= MAX_BYTES);
    let row_bytes = (u64::from(w) * info.bits_per_pixel() as u64).div_ceil(8);
    if !pixels_ok || row_bytes > MAX_ROW_BYTES {
        return Err(format!("PNG too large ({w}x{h})"));
    }
    // png 0.18 accepts a PLTE of any length and then panics expanding one
    // that is not whole RGB triples.
    if info.color_type == ColorType::Indexed
        && info.palette.as_ref().is_none_or(|p| p.len() % 3 != 0)
    {
        return Err("PNG palette is missing or not whole RGB entries".into());
    }
    Ok(reader)
}

/// Runs the decoder, turning a panic in it into an error: the checks in
/// `reader` cover the panics known in png 0.18, and this keeps an unknown one
/// from taking the VM down with it.
fn guarded<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|_| Err("PNG decoder failed".into()))
}

/// The width and height a PNG declares, if it is one `decode_png` accepts.
pub fn png_size(data: &[u8]) -> Result<(u32, u32), String> {
    guarded(|| Ok(reader(data)?.info().size()))
}

/// Decodes the first frame of a PNG to (width, height, 0xAARRGGBB pixels).
pub fn decode_png(data: &[u8]) -> Result<(u32, u32, Vec<u32>), String> {
    guarded(|| decode(data))
}

fn decode(data: &[u8]) -> Result<(u32, u32, Vec<u32>), String> {
    let mut reader = reader(data)?;
    let size = reader
        .output_buffer_size()
        .filter(|&n| n <= MAX_BYTES)
        .ok_or("PNG too large")?;
    let mut buf = vec![0; size];
    let info = reader.next_frame(&mut buf).map_err(|e| e.to_string())?;
    let (w, h) = (info.width as usize, info.height as usize);
    let channels = match info.color_type {
        ColorType::Grayscale => 1,
        ColorType::GrayscaleAlpha => 2,
        ColorType::Rgb => 3,
        ColorType::Rgba => 4,
        ColorType::Indexed => return Err("PNG palette was not expanded".into()),
    };
    let mut pixels = Vec::with_capacity(w * h);
    for row in buf.chunks(info.line_size).take(h) {
        for px in row.chunks_exact(channels).take(w) {
            pixels.push(argb(px));
        }
    }
    if pixels.len() != w * h {
        return Err("PNG data is truncated".into());
    }
    Ok((info.width, info.height, pixels))
}

/// One pixel of 1 to 4 channels (gray, gray+alpha, RGB, RGBA) as 0xAARRGGBB.
fn argb(px: &[u8]) -> u32 {
    let (r, g, b, a) = match *px {
        [y] => (y, y, y, 255),
        [y, a] => (y, y, y, a),
        [r, g, b] => (r, g, b, 255),
        [r, g, b, a, ..] => (r, g, b, a),
        [] => (0, 0, 0, 0),
    };
    u32::from_be_bytes([a, r, g, b])
}

#[cfg(test)]
mod tests {
    use super::{decode_png, png_size, reader};

    /// IHDR (16384x4096, 1-bit grayscale), an empty IDAT and IEND, with valid
    /// CRCs. 64M pixels is 256 MiB as ARGB though only 8 MiB as 1-bit samples.
    const HUGE_GRAY: &[u8] = &[
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44,
        0x52, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x10, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0xa2,
        0x66, 0xc4, 0x9e, 0x00, 0x00, 0x00, 0x00, 0x49, 0x44, 0x41, 0x54, 0x35, 0xaf, 0x06, 0x1e,
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82,
    ];

    #[test]
    fn rejects_garbage() {
        assert!(decode_png(b"not a png").is_err());
        assert!(decode_png(&[]).is_err());
        assert!(decode_png(b"\x89PNG\r\n\x1a\n\0\0\0\x0dIHDR").is_err());
    }

    /// 1x1, 8-bit indexed, a 4-byte PLTE and a valid IDAT: png 0.18 panics
    /// expanding it unless `reader` refuses it first.
    const SHORT_PALETTE: &[u8] = &[
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44,
        0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x03, 0x00, 0x00, 0x00, 0x28,
        0xcb, 0x34, 0xbb, 0x00, 0x00, 0x00, 0x04, 0x50, 0x4c, 0x54, 0x45, 0x00, 0x01, 0x02, 0x03,
        0x1e, 0x5e, 0xf7, 0x75, 0x00, 0x00, 0x00, 0x0a, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63,
        0x60, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01, 0x48, 0xaf, 0xa4, 0x71, 0x00, 0x00, 0x00, 0x00,
        0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82,
    ];

    /// IHDR 16777216x1 16-bit RGBA, empty IDAT, IEND: within the pixel cap,
    /// but one raw row is 128 MiB.
    const WIDE_RGBA16: &[u8] = &[
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44,
        0x52, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x10, 0x06, 0x00, 0x00, 0x00, 0x7d,
        0xd1, 0xaa, 0x71, 0x00, 0x00, 0x00, 0x00, 0x49, 0x44, 0x41, 0x54, 0x35, 0xaf, 0x06, 0x1e,
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, 0xae, 0x42, 0x60, 0x82,
    ];

    /// 1x1 RGBA 11223344 with an iCCP chunk whose profile inflates to 4 KiB.
    const WITH_ICCP: &[u8] = &[
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44,
        0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f,
        0x15, 0xc4, 0x89, 0x00, 0x00, 0x00, 0x1d, 0x69, 0x43, 0x43, 0x50, 0x78, 0x00, 0x00, 0x78,
        0xda, 0xed, 0xc1, 0x01, 0x0d, 0x00, 0x00, 0x00, 0xc2, 0xa0, 0xf7, 0x4f, 0x6d, 0x0f, 0x07,
        0x14, 0x00, 0x00, 0x00, 0xf0, 0x6e, 0x10, 0x00, 0x00, 0x01, 0x8e, 0x58, 0xd6, 0xda, 0x00,
        0x00, 0x00, 0x0d, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0x10, 0x54, 0x32, 0x76, 0x01,
        0x00, 0x01, 0x59, 0x00, 0xab, 0x13, 0x2a, 0x25, 0xab, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
        0x4e, 0x44, 0xae, 0x42, 0x60, 0x82,
    ];

    #[test]
    fn skips_the_icc_profile() {
        let info_profile = reader(WITH_ICCP).map(|r| r.info().icc_profile.is_some());
        assert_eq!(info_profile, Ok(false));
        assert_eq!(decode_png(WITH_ICCP), Ok((1, 1, vec![0x4411_2233])));
    }

    #[test]
    fn refuses_a_palette_of_partial_entries() {
        let err = decode_png(SHORT_PALETTE).expect_err("short palette");
        assert!(err.contains("palette"), "{err}");
        assert!(png_size(SHORT_PALETTE).is_err());
    }

    #[test]
    fn caps_the_raw_row() {
        let err = png_size(WIDE_RGBA16).expect_err("over the row cap");
        assert!(err.contains("too large"), "{err}");
    }

    #[test]
    fn caps_pixels_not_sample_bytes() {
        let err = png_size(HUGE_GRAY).expect_err("over the cap");
        assert!(err.contains("too large"), "{err}");
        assert!(decode_png(HUGE_GRAY).is_err());
    }
}
