//! A window with no screen, for tests and machines without a display.

use std::path::PathBuf;

use crate::{Event, Host, fit};

pub struct Headless {
    w: u32,
    h: u32,
    pixels: Vec<u32>,
    title: String,
    dump: Option<PathBuf>,
}

impl Headless {
    /// Fails, rather than aborting, when the frame cannot be allocated.
    pub fn new(w: u32, h: u32, dump: Option<PathBuf>) -> Result<Headless, String> {
        let n = w as usize * h as usize;
        let mut pixels = Vec::new();
        pixels
            .try_reserve_exact(n)
            .map_err(|_| format!("out of memory for a {w}x{h} window"))?;
        pixels.resize(n, 0);
        Ok(Headless {
            w,
            h,
            pixels,
            title: String::new(),
            dump,
        })
    }

    /// The window as a binary PPM (P6), the simplest format image tools read.
    fn ppm(&self) -> Result<Vec<u8>, String> {
        let mut out = format!("P6\n{} {}\n255\n", self.w, self.h).into_bytes();
        out.try_reserve_exact(self.pixels.len() * 3)
            .map_err(|_| "out of memory for the PPM dump".to_string())?;
        for p in &self.pixels {
            out.extend_from_slice(&[(p >> 16) as u8, (p >> 8) as u8, *p as u8]);
        }
        Ok(out)
    }
}

impl Host for Headless {
    fn present(&mut self, rgb: &[i32], w: usize, h: usize) -> Result<(), String> {
        let (dw, dh) = (self.w as usize, self.h as usize);
        fit(rgb, w, h, &mut self.pixels, dw, dh);
        Ok(())
    }

    fn poll(&mut self) -> Option<Event> {
        None
    }

    fn size(&self) -> (u32, u32) {
        (self.w, self.h)
    }

    fn capture(&self, f: &mut dyn FnMut(&[u32])) {
        f(&self.pixels);
    }

    fn set_title(&mut self, title: &str) {
        self.title = title.to_owned();
    }

    fn close(self: Box<Self>) -> Result<(), String> {
        match &self.dump {
            Some(path) => std::fs::write(path, self.ppm()?)
                .map_err(|e| format!("cannot write {}: {e}", path.display())),
            None => Ok(()),
        }
    }
}
