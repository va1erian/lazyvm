//! The hosts a window runs on: the winit window on Windows, and the
//! offscreen backend, which has no loop of its own (docs/ui.md, "Evidence").

use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::time::Instant;

use xui_canvas::OffscreenBackend;
use xui_core::backend::{Backend, Event, PlatformSpec, WindowId};
use xui_core::{Dip, Modifiers, MouseButton, Ui, run_app};

use crate::app::{JavaApp, Msg};
use crate::{Dispatch, Headless, Host, Stats, table, tree};

pub(crate) fn run(
    host: &Host,
    title: &str,
    (w, h): (u32, u32),
    tree: crate::Tree,
    dispatch: Dispatch,
) -> Result<Stats, String> {
    let start = Instant::now();
    let root = RefCell::new(Some(tree::decode(&tree)?));
    let stats = Rc::new(Cell::new(Stats::default()));
    let window = Rc::new(Cell::new(WindowId::NONE));
    let error = RefCell::new(None);
    let backend = match host {
        Host::Desktop(backend) => Rc::clone(backend),
        Host::Headless(script) => headless(script, &window, &stats),
    };
    let spec = PlatformSpec::new(title).size(Dip(w as f32), Dip(h as f32));
    let app_stats = Rc::clone(&stats);
    let made = run_app(backend, spec, |ui: &mut Ui<Msg>| {
        window.set(ui.window());
        if let Some(layout) = root.borrow_mut().take()
            && let Err(e) = ui.root(layout)
        {
            *error.borrow_mut() = Some(format!("cannot build the window: {e:?}"));
            ui.quit();
        }
        let mut s = app_stats.get();
        s.build_us = start.elapsed().as_micros() as u64;
        app_stats.set(s);
        JavaApp {
            dispatch,
            stopped: false,
            stats: app_stats,
        }
    });
    made.map_err(|e| format!("cannot open the window: {e:?}"))?;
    if let Some(e) = error.into_inner() {
        return Err(e);
    }
    let mut s = stats.get();
    s.sets = table::sets();
    Ok(s)
}

/// The offscreen backend, with a run hook that plays `script` once the
/// window is built and before it closes.
fn headless(
    script: &Headless,
    window: &Rc<Cell<WindowId>>,
    stats: &Rc<Cell<Stats>>,
) -> Rc<dyn Backend> {
    let backend = Rc::new(OffscreenBackend::new());
    let (b, window, stats) = (Rc::clone(&backend), Rc::clone(window), Rc::clone(stats));
    let (clicks, dump) = (script.clicks, script.dump.clone());
    backend.set_run_hook(move || {
        let start = Instant::now();
        let window = window.get();
        if let Some(target) = table::click_target() {
            let r = b.bounds(target);
            let (x, y) = ((r.left + r.right) / 2, (r.top + r.bottom) / 2);
            // Each click pays for its own frame: the label repaints.
            for _ in 0..clicks {
                click(&b, window, x, y);
                b.render(window);
            }
        }
        let frame = b.render(window);
        let mut s = stats.get();
        s.clicks_us = start.elapsed().as_micros() as u64;
        stats.set(s);
        if let (Some(path), Some(frame)) = (dump, frame) {
            let mut ppm = format!("P6\n{} {}\n255\n", frame.width, frame.height).into_bytes();
            ppm.extend(
                frame
                    .pixels
                    .as_chunks::<4>()
                    .0
                    .iter()
                    .flat_map(|p| [p[0], p[1], p[2]]),
            );
            if let Err(e) = std::fs::write(&path, ppm) {
                eprintln!("lazy.ui: cannot write {}: {e}", path.display());
            }
        }
    });
    backend
}

/// A left click at (x, y), then the wake the offscreen backend never sends,
/// so the queued message reaches `update`.
fn click(b: &OffscreenBackend, window: WindowId, x: i32, y: i32) {
    let (button, modifiers) = (MouseButton::Left, Modifiers::default());
    b.inject(
        window,
        Event::MouseDown {
            x,
            y,
            button,
            modifiers,
        },
    );
    b.inject(
        window,
        Event::MouseUp {
            x,
            y,
            button,
            modifiers,
        },
    );
    b.pump(window);
}
