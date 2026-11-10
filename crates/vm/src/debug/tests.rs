//! Garbage into every command: whatever the bytes, the answer is a reply or
//! an error code, never a panic, and nothing is allocated from a length the
//! packet only claims.

use super::wire::{Reader, Writer, err};
use crate::{ClassPath, Options, Vm};

/// A small deterministic generator, so a failure repeats.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }

    fn bytes(&mut self, len: usize) -> Vec<u8> {
        // Mostly small numbers and 0xff, which is where bounds checks fail.
        (0..len)
            .map(|_| match self.next() % 4 {
                0 => 0xff,
                1 => 0,
                _ => (self.next() % 256) as u8,
            })
            .collect()
    }
}

fn bare_vm() -> Vm {
    let classpath = ClassPath {
        boot: Vec::new(),
        app: Vec::new(),
    };
    Vm::new(classpath, Options::default())
}

#[test]
fn no_command_panics_on_garbage() {
    let mut vm = bare_vm();
    let mut rng = Rng(0x9e37_79b9_7f4a_7c15);
    for set in 0..=70u8 {
        for cmd in 0..=22u8 {
            if (set, cmd) == (1, 10) {
                continue; // VirtualMachine.Exit ends the process
            }
            for round in 0..40 {
                let len = (round * 3) % 60;
                let data = rng.bytes(len);
                let mut out = Writer::default();
                // Whatever it answers, it must come back.
                let _ = vm.dispatch(set, cmd, &mut Reader::new(&data), &mut out);
            }
        }
    }
}

#[test]
fn unknown_commands_are_not_implemented() {
    let mut vm = bare_vm();
    let mut out = Writer::default();
    let answer = vm.dispatch(99, 1, &mut Reader::new(&[]), &mut out);
    assert_eq!(answer, Err(err::NOT_IMPLEMENTED));
    let answer = vm.dispatch(1, 200, &mut Reader::new(&[]), &mut out);
    assert_eq!(answer, Err(err::NOT_IMPLEMENTED));
}

#[test]
fn a_claimed_count_larger_than_the_packet_is_refused() {
    let mut vm = bare_vm();
    // DisposeObjects with two billion requests and no bytes behind them.
    let data = [0x7f, 0xff, 0xff, 0xff];
    let mut out = Writer::default();
    let answer = vm.dispatch(1, 14, &mut Reader::new(&data), &mut out);
    assert_eq!(answer, Err(err::ILLEGAL_ARGUMENT));
    // EventRequest.Set with a modifier count that does not fit.
    let data = [2, 2, 0x7f, 0xff, 0xff, 0xff];
    let answer = vm.dispatch(15, 1, &mut Reader::new(&data), &mut out);
    assert_eq!(answer, Err(err::ILLEGAL_ARGUMENT));
}

#[test]
fn objects_the_debugger_never_saw_are_invalid() {
    let mut vm = bare_vm();
    let mut out = Writer::default();
    let mut data = 77u64.to_be_bytes().to_vec();
    data.extend_from_slice(&0i32.to_be_bytes());
    for (set, cmd) in [(9, 1), (10, 1), (13, 1), (11, 4), (16, 3)] {
        let answer = vm.dispatch(set, cmd, &mut Reader::new(&data), &mut out);
        assert!(answer.is_err(), "set {set} command {cmd}");
    }
}
