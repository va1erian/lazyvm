//! winit keys to `lazy.gfx.Key` codes, and typed text to TEXT events.

use winit::event::{ElementState, KeyEvent};
use winit::keyboard::{KeyCode, ModifiersState, PhysicalKey};

use super::EventQueue;
use crate::Event;

/// Queues the key event and, on a press, the text it typed. winit gives text
/// for shortcuts too (Ctrl+S is "s") and for editing keys (Enter is "\r"),
/// so those are dropped: a chord with Ctrl or Alt alone types nothing, and
/// control characters are never text. Ctrl and Alt together is AltGr on
/// Windows, which does type (`@` on many European layouts).
pub fn push(events: &mut EventQueue, event: &KeyEvent, mods: ModifiersState) {
    let code = match event.physical_key {
        PhysicalKey::Code(c) => code(c),
        PhysicalKey::Unidentified(_) => 0,
    };
    match event.state {
        ElementState::Pressed => {
            events.push(Event::KeyDown(code));
            if let Some(text) = &event.text {
                for unit in typed(text, mods) {
                    events.push(Event::Text(unit));
                }
            }
        }
        ElementState::Released => events.push(Event::KeyUp(code)),
    }
}

/// The UTF-16 units a key press with `text` types under `mods` (see `push`).
fn typed(text: &str, mods: ModifiersState) -> impl Iterator<Item = u16> + '_ {
    let chord = mods.control_key() != mods.alt_key();
    text.chars()
        .filter(move |c| !chord && !c.is_control())
        .flat_map(|c| {
            let mut units = [0; 2];
            c.encode_utf16(&mut units).to_vec()
        })
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

#[cfg(test)]
mod tests {
    use super::typed;
    use winit::keyboard::ModifiersState as M;

    fn units(text: &str, mods: M) -> Vec<u16> {
        typed(text, mods).collect()
    }

    #[test]
    fn plain_and_shifted_keys_type() {
        assert_eq!(units("a", M::empty()), [u16::from(b'a')]);
        assert_eq!(units("A", M::SHIFT), [u16::from(b'A')]);
        assert_eq!(units("\u{1F600}", M::empty()), [0xD83D, 0xDE00]);
    }

    #[test]
    fn shortcuts_and_control_characters_do_not() {
        assert!(units("s", M::CONTROL).is_empty());
        assert!(units("f", M::ALT).is_empty());
        assert!(units("\r", M::empty()).is_empty());
        assert!(units("\u{8}", M::empty()).is_empty());
        assert!(units("\u{1b}", M::empty()).is_empty());
    }

    #[test]
    fn altgr_types() {
        assert_eq!(units("@", M::CONTROL | M::ALT), [u16::from(b'@')]);
    }
}
