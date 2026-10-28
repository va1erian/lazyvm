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
    pub fn new(w: u32, h: u32, dump: Option<PathBuf>) -> Headless {
        Headless {
            w,
            h,
            pixels: vec![0; w as usize * h as usize],
            title: String::new(),
            dump,
        }
    }

    /// The window as a binary PPM (P6), the simplest format image tools read.
    fn ppm(&self) -> Vec<u8> {
        let mut out = format!("P6\n{} {}\n255\n", self.w, self.h).into_bytes();
        out.reserve(self.pixels.len() * 3);
        for p in &self.pixels {
            out.extend_from_slice(&[(p >> 16) as u8, (p >> 8) as u8, *p as u8]);
        }
        out
    }
}

impl Host for Headless {
    fn present(&mut self, rgb: &[u32], w: usize, h: usize) -> Result<(), String> {
        let (dw, dh) = (self.w as usize, self.h as usize);
        fit(rgb, w, h, &mut self.pixels, dw, dh);
        Ok(())
    }

    fn poll(&mut self) -> Option<Event> {
        None
    }

    fn is_open(&self) -> bool {
        true
    }

    fn size(&self) -> (u32, u32) {
        (self.w, self.h)
    }

    fn capture(&self) -> Vec<u32> {
        self.pixels.clone()
    }

    fn set_title(&mut self, title: &str) {
        self.title = title.to_owned();
    }

    fn close(self: Box<Self>) -> Result<(), String> {
        match &self.dump {
            Some(path) => std::fs::write(path, self.ppm())
                .map_err(|e| format!("cannot write {}: {e}", path.display())),
            None => Ok(()),
        }
    }
}
