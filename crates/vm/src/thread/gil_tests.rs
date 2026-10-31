//! Who goes next, on the line alone. `tests/java/thread` runs the rest; these
//! pin the orders that a program can only see as lost time.

use std::sync::Arc;
use std::time::{Duration, Instant};

use super::{Gil, Line, SLICE, Slot};
use crate::Vm;

fn line(slice_spent: bool, slice_was_woken: bool) -> Slot {
    let now = Instant::now();
    let slice_start = if slice_spent {
        now.checked_sub(SLICE + Duration::from_millis(1))
            .unwrap_or(now)
    } else {
        now
    };
    Slot {
        vm: None,
        seats: Vec::new(),
        preempted: Default::default(),
        arrived: Default::default(),
        expected: 0,
        next_place: 0,
        slice_start,
        slice_was_woken,
        served_at: now,
    }
}

fn name(slot: &mut Slot, thread: usize) {
    slot.new_place(thread);
    slot.expected += 1;
}

#[test]
fn a_spent_slice_goes_to_the_side_that_did_not_have_it() {
    let mut slot = line(true, true);
    slot.join(1, Line::Preempted);
    slot.join(2, Line::Woken);
    assert_eq!(slot.next_up(), Some(1));
    slot.slice_was_woken = false;
    assert_eq!(slot.next_up(), Some(2));
}

/// A holder that had the whole slice to itself.
fn busy_holder(slot: &mut Slot) {
    slot.served_at = line(true, true).slice_start;
}

#[test]
fn a_busy_holder_preempted_alone_goes_after_a_waiting_woken_thread() {
    let mut slot = line(true, true);
    slot.join(2, Line::Woken);
    busy_holder(&mut slot);
    slot.preempt_holder(1);
    assert_eq!(slot.preempted.front(), Some(&1));
    assert_eq!(slot.next_up(), Some(2));
}

#[test]
fn a_busy_holder_preempted_behind_another_goes_after_it() {
    let mut slot = line(true, true);
    slot.join(3, Line::Preempted);
    slot.join(2, Line::Woken);
    busy_holder(&mut slot);
    slot.preempt_holder(1);
    assert_eq!(slot.next_up(), Some(3));
    assert_eq!(slot.preempted.back(), Some(&1));
}

#[test]
fn a_holder_that_shared_its_slice_stays_with_the_woken_threads() {
    let mut slot = line(true, true);
    slot.join(3, Line::Preempted);
    slot.join(2, Line::Woken);
    slot.preempt_holder(1);
    assert_eq!(slot.preempted.len(), 1, "only the thread that was there");
    assert_eq!(slot.next_up(), Some(3), "the busy side's turn");
    slot.serve(3);
    slot.slice_start = line(true, true).slice_start;
    assert_eq!(slot.next_up(), Some(2));
    slot.serve(2);
    slot.slice_start = line(true, true).slice_start;
    assert_eq!(slot.next_up(), Some(1));
}

#[test]
fn a_slice_since_it_blocked_makes_a_woken_thread_a_busy_one() {
    let mut slot = line(true, true);
    slot.join(1, Line::Woken);
    slot.serve(1);
    slot.slice_was_woken = true;
    busy_holder(&mut slot);
    slot.preempt_holder(1);
    assert_eq!(slot.preempted.front(), Some(&1));
    slot.preempted.clear();
    slot.seat(1).ran = SLICE - Duration::from_micros(1);
    slot.served_at = Instant::now();
    slot.slice_was_woken = true;
    slot.seat(1).ran += SLICE;
    slot.preempt_holder(1);
    assert_eq!(slot.preempted.front(), Some(&1), "added up over its turns");
    slot.preempted.clear();
    slot.join(1, Line::Woken);
    assert_eq!(slot.seat(1).ran, Duration::ZERO, "blocking starts it afresh");
}

#[test]
fn woken_threads_go_in_the_order_they_were_woken() {
    let mut slot = line(false, true);
    name(&mut slot, 1);
    name(&mut slot, 2);
    slot.join(2, Line::Woken);
    assert_eq!(slot.next_up(), Some(2), "the one that is there");
    slot.join(1, Line::Woken);
    assert_eq!(slot.next_up(), Some(1), "its place was kept");
    slot.serve(1);
    assert_eq!(slot.next_up(), Some(2));
    assert_eq!(slot.expected, 0);
}

#[test]
fn a_thread_leaves_the_line_with_no_place() {
    let mut slot = line(false, true);
    name(&mut slot, 1);
    slot.join(1, Line::Woken);
    assert!(slot.seat(1).place.is_some());
    slot.serve(1);
    assert!(slot.seat(1).place.is_none());
    assert!(!slot.anyone_in_line());
}

#[test]
fn a_slice_with_time_left_waits_for_a_thread_on_its_way() {
    let mut slot = line(false, true);
    slot.join(1, Line::Preempted);
    name(&mut slot, 2);
    assert_eq!(slot.next_up(), None);
    slot.slice_start = line(true, true).slice_start;
    assert_eq!(slot.next_up(), Some(1));
}

/// A free VM with a spent slice can name a preempted thread that is asleep
/// while a woken thread arrives, and that arrival is all that happens: the one
/// who arrives must wake the thread named.
#[test]
fn an_arrival_wakes_the_thread_the_line_names() {
    let gil = Gil::new();
    let mut slot = line(true, true);
    slot.vm = Some(Vm::placeholder(Arc::clone(&gil)));
    slot.join(1, Line::Preempted);
    slot.join(2, Line::Woken);
    let turn = slot.to_notify(Some(2));
    assert!(turn.is_some_and(|t| Arc::ptr_eq(&t, &slot.seats[1].turn)));
    assert!(slot.to_notify(Some(1)).is_none(), "not itself");
}

/// A free VM kept for a thread on its way: the first preempted thread is told,
/// so that it can time the slice.
#[test]
fn a_release_kept_for_a_thread_on_its_way_tells_the_first_preempted() {
    let gil = Gil::new();
    let mut slot = line(false, true);
    slot.vm = Some(Vm::placeholder(Arc::clone(&gil)));
    slot.join(1, Line::Preempted);
    slot.join(3, Line::Preempted);
    name(&mut slot, 2);
    let turn = slot.to_notify(None);
    assert!(turn.is_some_and(|t| Arc::ptr_eq(&t, &slot.seats[1].turn)));
}
