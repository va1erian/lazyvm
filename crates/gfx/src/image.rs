//! PNG decoding for `lazy.gfx.Image`. Input is untrusted: every failure is an
//! error string, and the decoder's allocation limit bounds memory.

use std::io::Cursor;

use png::{ColorType, Decoder, Limits, Transformations};

/// The largest decoded image, in bytes of 8-bit RGBA, that `decode_png` accepts.
const MAX_BYTES: usize = 64 << 20;

/// Decodes the first frame of a PNG to (width, height, 0xAARRGGBB pixels).
pub fn decode_png(data: &[u8]) -> Result<(u32, u32, Vec<u32>), String> {
    let mut decoder = Decoder::new_with_limits(Cursor::new(data), Limits { bytes: MAX_BYTES });
    decoder.set_transformations(Transformations::normalize_to_color8());
    let mut reader = decoder.read_info().map_err(|e| e.to_string())?;
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
    use super::decode_png;

    #[test]
    fn rejects_garbage() {
        assert!(decode_png(b"not a png").is_err());
        assert!(decode_png(&[]).is_err());
        assert!(decode_png(b"\x89PNG\r\n\x1a\n\0\0\0\x0dIHDR").is_err());
    }
}
