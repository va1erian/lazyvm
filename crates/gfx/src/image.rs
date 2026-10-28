//! PNG decoding for `lazy.gfx.Image`. Input is untrusted: every failure is an
//! error string. Memory is bounded by a cap on the pixel count, checked from
//! the header before any pixel buffer exists; the png crate's own limit only
//! covers its internal buffers.

use std::io::Cursor;

use png::{ColorType, Decoder, Limits, Reader, Transformations};

/// The largest image, as 0xAARRGGBB pixels (4 bytes each), that is decoded.
const MAX_BYTES: usize = 64 << 20;

/// Reads the header, refusing images over the cap whatever their format.
fn reader(data: &[u8]) -> Result<Reader<Cursor<&[u8]>>, String> {
    let mut decoder = Decoder::new_with_limits(Cursor::new(data), Limits { bytes: MAX_BYTES });
    decoder.set_transformations(Transformations::normalize_to_color8());
    let reader = decoder.read_info().map_err(|e| e.to_string())?;
    let (w, h) = reader.info().size();
    let bytes = (w as usize)
        .checked_mul(h as usize)
        .and_then(|n| n.checked_mul(4));
    match bytes {
        Some(n) if n <= MAX_BYTES => Ok(reader),
        _ => Err(format!("PNG too large ({w}x{h})")),
    }
}

/// The width and height a PNG declares, if it is one `decode_png` accepts.
pub fn png_size(data: &[u8]) -> Result<(u32, u32), String> {
    Ok(reader(data)?.info().size())
}

/// Decodes the first frame of a PNG to (width, height, 0xAARRGGBB pixels).
pub fn decode_png(data: &[u8]) -> Result<(u32, u32, Vec<u32>), String> {
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
    use super::{decode_png, png_size};

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

    #[test]
    fn caps_pixels_not_sample_bytes() {
        let err = png_size(HUGE_GRAY).expect_err("over the cap");
        assert!(err.contains("too large"), "{err}");
        assert!(decode_png(HUGE_GRAY).is_err());
    }
}
