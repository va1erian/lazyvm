//! winit keys to `lazy.gfx.Key` codes, and typed text to TEXT events.

use std::collections::VecDeque;

use winit::event::{ElementState, KeyEvent};
use winit::keyboard::{KeyCode, PhysicalKey};

use crate::Event;

/// Queues the key event and, on a press, the text it typed.
pub fn push(events: &mut VecDeque<Event>, event: &KeyEvent) {
    let code = match event.physical_key {
        PhysicalKey::Code(c) => code(c),
        PhysicalKey::Unidentified(_) => 0,
    };
    match event.state {
        ElementState::Pressed => {
            events.push_back(Event::KeyDown(code));
            if let Some(text) = &event.text {
                events.extend(text.encode_utf16().map(Event::Text));
            }
        }
        ElementState::Released => events.push_back(Event::KeyUp(code)),
    }
}

/// The `lazy.gfx.Key` constant for a physical key; 0 (UNKNOWN) when it has none.
fn code(c: KeyCode) -> i32 {
    use KeyCode::*;
    let letters = [
        KeyA, KeyB, KeyC, KeyD, KeyE, KeyF, KeyG, KeyH, KeyI, KeyJ, KeyK, KeyL, KeyM, KeyN, KeyO,
        KeyP, KeyQ, KeyR, KeyS, KeyT, KeyU, KeyV, KeyW, KeyX, KeyY, KeyZ,
    ];
    let digits = [
        Digit0, Digit1, Digit2, Digit3, Digit4, Digit5, Digit6, Digit7, Digit8, Digit9,
    ];
    let functions = [F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12];
    if let Some(i) = letters.iter().position(|&k| k == c) {
        return 'A' as i32 + i as i32;
    }
    if let Some(i) = digits.iter().position(|&k| k == c) {
        return '0' as i32 + i as i32;
    }
    if let Some(i) = functions.iter().position(|&k| k == c) {
        return 280 + i as i32;
    }
    match c {
        Space => ' ' as i32,
        Quote => '\'' as i32,
        Comma => ',' as i32,
        Minus => '-' as i32,
        Period => '.' as i32,
        Slash => '/' as i32,
        Semicolon => ';' as i32,
        Equal => '=' as i32,
        BracketLeft => '[' as i32,
        Backslash => '\\' as i32,
        BracketRight => ']' as i32,
        Backquote => '`' as i32,
        Escape => 256,
        Enter | NumpadEnter => 257,
        Tab => 258,
        Backspace => 259,
        Insert => 260,
        Delete => 261,
        ArrowRight => 262,
        ArrowLeft => 263,
        ArrowDown => 264,
        ArrowUp => 265,
        PageUp => 266,
        PageDown => 267,
        Home => 268,
        End => 269,
        ShiftLeft | ShiftRight => 270,
        ControlLeft | ControlRight => 271,
        AltLeft | AltRight => 272,
        _ => 0,
    }
}
