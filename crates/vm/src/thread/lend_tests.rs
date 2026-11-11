//! The lend on its own: the VM goes out, comes back for each callback, and is
//! never held twice. `tests/java/lazy/ui` runs it under real windows.

use super::with_lent;
use crate::{ClassPath, Options, Vm};

fn vm() -> Vm {
    Vm::new(ClassPath::default(), Options::default())
}

#[test]
fn nothing_is_lent_outside_a_lend() {
    assert!(with_lent(|_| ()).is_none());
}

#[test]
fn a_callback_gets_the_vm_and_its_own_callbacks_do_not() {
    let mut vm = vm();
    let marker = vm.current;
    let (outer, inner) = vm.lend(|| {
        let mut inner = None;
        let outer = with_lent(|vm| {
            inner = Some(with_lent(|_| ()).is_none());
            vm.current
        });
        (outer, inner)
    });
    assert_eq!(outer, Some(marker));
    assert_eq!(inner, Some(true), "a callback's own with_lent finds nothing");
    // The VM is whole again once the lend returns.
    assert_eq!(vm.current, marker);
    assert!(with_lent(|_| ()).is_none());
}

#[test]
fn every_callback_takes_the_vm_back() {
    let mut vm = vm();
    let calls = vm.lend(|| (0..100).filter_map(|_| with_lent(|_| ())).count());
    assert_eq!(calls, 100);
}

#[test]
fn a_nested_lend_stacks_on_top() {
    let mut vm = vm();
    let got = vm.lend(|| {
        with_lent(|vm| vm.lend(|| with_lent(|_| 7)))
    });
    assert_eq!(got, Some(Some(7)));
}
