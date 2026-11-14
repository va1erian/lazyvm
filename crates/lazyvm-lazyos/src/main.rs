// SPDX-License-Identifier: GPL-3.0-or-later
//! `lazyvm-ui` for LazyOS: the LazyVM launcher with `lazy.ui`, whose windows
//! are xuid clients (or own the display when no compositor runs) through
//! `xui-app`'s `LazyOSBackend` (docs/ui.md, "Backends"; S9-04).
//!
//! The fonts are read from the system's font folder at start, not bundled,
//! so they do not count against the binary's size (docs/ui.md, "Size").
//! Evidence on the serial console: `LAZYVM:UI:UP:PASS` once the first frame
//! is on screen, `LAZYVM:UI:BIND:FAIL:<code>` when the display cannot be
//! reached.

use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::rc::Rc;

use natives_ui::{Host, UiHost};
use xui_app::backend::LazyOSBackend;

/// Where LazyOS keeps the fonts xui apps draw with; the UI face first.
const FONT_DIR: &str = "/system/share/fonts/xui";
const UI_FONT: &str = "DroidSans.ttf";

mod messenger;
mod serve;
mod topics;

const HOST: UiHost = UiHost { desktop, error_box };

fn main() -> ExitCode {
    platform::messenger::install(messenger::factory);
    launcher::main(&[|vm| natives_ui::register(vm, HOST)])
}

/// The report of an uncaught exception: on the serial console for now; a
/// LazyOS notification would need Messenger (S10).
fn error_box(title: &str, text: &str) {
    println!("LAZYVM:UI:UNCAUGHT:{title}");
    eprintln!("{text}");
}

/// The UI face's bytes, after registering the folder's other faces beside it.
fn fonts(dir: &Path) -> Result<Vec<u8>, String> {
    let ui = std::fs::read(dir.join(UI_FONT))
        .map_err(|e| format!("{}: {e}", dir.join(UI_FONT).display()))?;
    let mut others: Vec<PathBuf> = std::fs::read_dir(dir)
        .map_err(|e| format!("{}: {e}", dir.display()))?
        .filter_map(|entry| entry.ok().map(|e| e.path()))
        .filter(|p| p.extension().is_some_and(|x| x == "ttf") && !p.ends_with(UI_FONT))
        .collect();
    others.sort();
    for path in others {
        // A face that cannot be read only falls back to the UI face.
        if let Ok(bytes) = std::fs::read(&path) {
            xui_app::font::add(bytes);
        }
    }
    // The first face registered would be the default; the UI face is.
    xui_canvas::set_default_family(xui_app::font::UI_FAMILY);
    Ok(ui)
}

/// The xuid host: connects (client, or owner when no compositor runs) with
/// the fonts from disk.
fn desktop() -> Result<Host, String> {
    let dir =
        std::env::var_os("LAZYVM_UI_FONTS").map_or_else(|| PathBuf::from(FONT_DIR), PathBuf::from);
    let ui = fonts(&dir)?;
    let backend = LazyOSBackend::connect_with_font(ui).map_err(|code| {
        println!("LAZYVM:UI:BIND:FAIL:{code}");
        format!("cannot reach the display (error {code})")
    })?;
    backend.on_first_frame(|| println!("LAZYVM:UI:UP:PASS"));
    Ok(Host::Desktop(Rc::new(backend)))
}
