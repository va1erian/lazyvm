//! Real windows on Windows: winit for the window and input, softbuffer to put
//! pixels on screen (the same stack as xui-canvas, so S9 can move onto xui
//! without a second one).
//!
//! winit allows one event loop per process and never gives it back, so it
//! lives in a thread local of the VM thread and is pumped, without waiting,
//! whenever the program presents or polls. One window at a time for now.

use std::cell::RefCell;
use std::collections::VecDeque;
use std::num::NonZeroU32;
use std::rc::Rc;
use std::time::Duration;

use winit::application::ApplicationHandler;
use winit::dpi::PhysicalSize;
use winit::event::{ElementState, MouseButton, WindowEvent};
use winit::event_loop::{ActiveEventLoop, EventLoop};
use winit::platform::pump_events::EventLoopExtPumpEvents;
use winit::platform::windows::EventLoopBuilderExtWindows;
use winit::window::{Window, WindowId};

use crate::{Event, Host, fit};

mod keys;

type Surface = softbuffer::Surface<Rc<Window>, Rc<Window>>;

/// The open window's state, filled in by winit callbacks.
struct App {
    request: Option<(String, u32, u32)>,
    window: Option<(Rc<Window>, Surface)>,
    error: Option<String>,
    events: VecDeque<Event>,
    cursor: (i32, i32),
    /// The last frame put on screen, for `capture`.
    last: (Vec<u32>, u32, u32),
}

impl App {
    fn create(&mut self, el: &ActiveEventLoop, title: &str, w: u32, h: u32) -> Result<(), String> {
        let attrs = Window::default_attributes()
            .with_title(title)
            .with_inner_size(PhysicalSize::new(w, h));
        let window = Rc::new(el.create_window(attrs).map_err(|e| e.to_string())?);
        let context = softbuffer::Context::new(window.clone()).map_err(|e| e.to_string())?;
        let surface = Surface::new(&context, window.clone()).map_err(|e| e.to_string())?;
        self.window = Some((window, surface));
        Ok(())
    }

    /// Forgets the previous window's events, cursor and last frame.
    fn reset(&mut self) {
        self.events.clear();
        self.cursor = (0, 0);
        self.last = (Vec::new(), 0, 0);
    }

    fn push_mouse(&mut self, button: MouseButton, state: ElementState) {
        let b = match button {
            MouseButton::Left => 1,
            MouseButton::Right => 2,
            MouseButton::Middle => 3,
            _ => return,
        };
        let (x, y) = self.cursor;
        self.events.push_back(match state {
            ElementState::Pressed => Event::MouseDown(x, y, b),
            ElementState::Released => Event::MouseUp(x, y, b),
        });
    }
}

impl ApplicationHandler for App {
    fn resumed(&mut self, el: &ActiveEventLoop) {
        self.about_to_wait(el);
    }

    fn about_to_wait(&mut self, el: &ActiveEventLoop) {
        if let Some((title, w, h)) = self.request.take()
            && let Err(e) = self.create(el, &title, w, h)
        {
            self.error = Some(e);
        }
    }

    fn window_event(&mut self, _: &ActiveEventLoop, _: WindowId, event: WindowEvent) {
        let pushed = match event {
            WindowEvent::CloseRequested => Event::Close,
            WindowEvent::Focused(f) => Event::Focus(f),
            WindowEvent::Resized(s) => Event::Resize(s.width as i32, s.height as i32),
            WindowEvent::CursorMoved { position, .. } => {
                self.cursor = (position.x as i32, position.y as i32);
                Event::MouseMove(self.cursor.0, self.cursor.1)
            }
            WindowEvent::MouseInput { state, button, .. } => {
                self.push_mouse(button, state);
                return;
            }
            WindowEvent::KeyboardInput { event, .. } => {
                keys::push(&mut self.events, &event);
                return;
            }
            _ => return,
        };
        self.events.push_back(pushed);
    }
}

struct Desktop {
    event_loop: EventLoop<()>,
    app: App,
}

impl Desktop {
    fn pump(&mut self) {
        self.event_loop
            .pump_app_events(Some(Duration::ZERO), &mut self.app);
    }
}

thread_local! {
    static DESKTOP: RefCell<Option<Desktop>> = const { RefCell::new(None) };
}

fn with_desktop<T>(f: impl FnOnce(&mut Desktop) -> T) -> Result<T, String> {
    DESKTOP.with_borrow_mut(|d| {
        if d.is_none() {
            let event_loop = EventLoop::builder()
                .with_any_thread(true)
                .build()
                .map_err(|e| e.to_string())?;
            *d = Some(Desktop {
                event_loop,
                app: App {
                    request: None,
                    window: None,
                    error: None,
                    events: VecDeque::new(),
                    cursor: (0, 0),
                    last: (Vec::new(), 0, 0),
                },
            });
        }
        d.as_mut().map(f).ok_or_else(|| "no event loop".to_string())
    })
}

/// The handle-table entry for the one desktop window, whose state is in `DESKTOP`.
pub struct DesktopWindow;

impl DesktopWindow {
    pub fn open(title: &str, w: u32, h: u32) -> Result<DesktopWindow, String> {
        with_desktop(|d| {
            if d.app.window.is_some() {
                return Err("only one window at a time for now".to_string());
            }
            d.app.request = Some((title.to_owned(), w, h));
            d.app.reset();
            // The window is created from inside the loop; a few pumps get there.
            for _ in 0..100 {
                d.pump();
                if let Some(e) = d.app.error.take() {
                    return Err(e);
                }
                if d.app.window.is_some() {
                    return Ok(DesktopWindow);
                }
            }
            Err("the window system did not create the window".to_string())
        })?
    }
}

impl Host for DesktopWindow {
    fn present(&mut self, rgb: &[u32], w: usize, h: usize) -> Result<(), String> {
        with_desktop(|d| {
            d.pump();
            let Some((window, surface)) = d.app.window.as_mut() else {
                return Ok(());
            };
            let size = window.inner_size();
            let (Some(dw), Some(dh)) = (NonZeroU32::new(size.width), NonZeroU32::new(size.height))
            else {
                return Ok(()); // minimized
            };
            surface.resize(dw, dh).map_err(|e| e.to_string())?;
            let mut buffer = surface.buffer_mut().map_err(|e| e.to_string())?;
            fit(rgb, w, h, &mut buffer, dw.get() as usize, dh.get() as usize);
            d.app.last = (buffer.to_vec(), dw.get(), dh.get());
            buffer.present().map_err(|e| e.to_string())
        })?
    }

    fn poll(&mut self) -> Option<Event> {
        with_desktop(|d| {
            if d.app.events.is_empty() {
                d.pump();
            }
            d.app.events.pop_front()
        })
        .ok()
        .flatten()
    }

    fn size(&self) -> (u32, u32) {
        with_desktop(|d| match &d.app.window {
            Some((w, _)) => (w.inner_size().width, w.inner_size().height),
            None => (0, 0),
        })
        .unwrap_or((0, 0))
    }

    fn capture(&self, f: &mut dyn FnMut(&[u32])) {
        // After a resize the last frame no longer matches the window: report none.
        let size = self.size();
        let _ = with_desktop(|d| {
            let (pixels, w, h) = &d.app.last;
            f(if (*w, *h) == size { pixels } else { &[] });
        });
    }

    fn set_title(&mut self, title: &str) {
        let _ = with_desktop(|d| {
            if let Some((w, _)) = &d.app.window {
                w.set_title(title);
            }
        });
    }

    fn close(self: Box<Self>) -> Result<(), String> {
        with_desktop(|d| {
            d.app.window = None;
            d.app.reset();
            d.pump();
        })
    }
}
