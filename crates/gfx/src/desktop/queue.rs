//! The input waiting for `poll`: bounded, with runs of mouse moves merged.

use std::collections::VecDeque;

use crate::Event;

/// The most input events kept waiting for `poll`.
const MAX_EVENTS: usize = 4096;

#[derive(Default)]
pub struct EventQueue {
    events: VecDeque<Event>,
}

impl EventQueue {
    /// Queues `e`. Only the latest of consecutive mouse moves is kept, and a
    /// program that never polls loses its oldest input rather than growing
    /// the queue without end.
    pub fn push(&mut self, e: Event) {
        match (self.events.back_mut(), e) {
            (Some(last @ Event::MouseMove(..)), Event::MouseMove(..)) => *last = e,
            _ => self.events.push_back(e),
        }
        let excess = self.events.len().saturating_sub(MAX_EVENTS);
        self.events.drain(..excess);
    }

    pub fn pop(&mut self) -> Option<Event> {
        self.events.pop_front()
    }

    pub fn is_empty(&self) -> bool {
        self.events.is_empty()
    }

    pub fn clear(&mut self) {
        self.events.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::{EventQueue, MAX_EVENTS};
    use crate::Event;

    #[test]
    fn merges_runs_of_mouse_moves() {
        let mut q = EventQueue::default();
        q.push(Event::MouseMove(1, 1));
        q.push(Event::MouseMove(2, 2));
        q.push(Event::KeyDown('A' as i32));
        q.push(Event::MouseMove(3, 3));
        q.push(Event::MouseMove(4, 4));
        let all: Vec<_> = std::iter::from_fn(|| q.pop()).collect();
        let expected = [
            Event::MouseMove(2, 2),
            Event::KeyDown('A' as i32),
            Event::MouseMove(4, 4),
        ];
        assert_eq!(all, expected);
    }

    #[test]
    fn drops_the_oldest_past_the_cap() {
        let mut q = EventQueue::default();
        for i in 0..MAX_EVENTS as i32 + 10 {
            q.push(Event::KeyDown(i));
        }
        assert_eq!(q.pop(), Some(Event::KeyDown(10)));
        let rest = std::iter::from_fn(|| q.pop()).count();
        assert_eq!(rest, MAX_EVENTS - 1);
    }
}
