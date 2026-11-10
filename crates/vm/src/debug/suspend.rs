//! Suspend counts. JDWP counts suspends of the whole VM and of single threads,
//! and a thread runs again only when its count is back to zero. A stop of "all
//! threads" is kept as one counter plus one per thread, so threads that start
//! meanwhile are held too.

use std::collections::HashMap;

#[derive(Default)]
pub struct Suspend {
    all: u32,
    per_thread: HashMap<usize, u32>,
}

impl Suspend {
    pub fn held(&self, thread: usize) -> bool {
        self.all > 0 || self.per_thread.get(&thread).is_some_and(|&n| n > 0)
    }

    pub fn count(&self, thread: usize) -> u32 {
        self.all + self.per_thread.get(&thread).copied().unwrap_or(0)
    }

    #[cfg(test)]
    pub fn is_clear(&self) -> bool {
        self.all == 0 && self.per_thread.is_empty()
    }

    pub fn suspend_all(&mut self) {
        self.all += 1;
    }

    pub fn suspend_thread(&mut self, thread: usize) {
        *self.per_thread.entry(thread).or_insert(0) += 1;
    }

    /// `VirtualMachine.Resume`: one count off every thread.
    pub fn resume_all(&mut self) {
        self.all = self.all.saturating_sub(1);
        self.per_thread.retain(|_, n| {
            *n -= 1;
            *n > 0
        });
    }

    /// `ThreadReference.Resume`. A thread held only by an all-threads stop
    /// gets that count off alone: the stop is turned into one count on each of
    /// the other `live` threads, which keeps them held.
    pub fn resume_thread(&mut self, thread: usize, live: &[usize]) {
        match self.per_thread.get_mut(&thread) {
            Some(n) if *n > 0 => {
                *n -= 1;
                if *n == 0 {
                    self.per_thread.remove(&thread);
                }
            }
            _ if self.all > 0 => {
                self.all -= 1;
                for &other in live.iter().filter(|&&t| t != thread) {
                    self.suspend_thread(other);
                }
            }
            _ => {}
        }
    }

    /// The thread is gone; its own count goes with it.
    pub fn forget_thread(&mut self, thread: usize) {
        self.per_thread.remove(&thread);
    }

    pub fn clear(&mut self) {
        *self = Suspend::default();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_must_all_be_resumed() {
        let mut s = Suspend::default();
        s.suspend_all();
        s.suspend_thread(2);
        assert!(s.held(1) && s.held(2));
        assert_eq!(s.count(2), 2);
        s.resume_all();
        assert!(!s.held(1) && !s.held(2));
        assert!(s.is_clear());
    }

    #[test]
    fn resuming_one_thread_of_an_all_stop_keeps_the_others_held() {
        let mut s = Suspend::default();
        s.suspend_all();
        s.resume_thread(2, &[1, 2, 3]);
        assert!(!s.held(2) && s.held(1) && s.held(3));
        s.suspend_thread(2);
        s.resume_all();
        assert!(s.is_clear());
    }

    #[test]
    fn resuming_a_thread_that_is_not_held_does_nothing() {
        let mut s = Suspend::default();
        s.resume_thread(4, &[4]);
        s.resume_all();
        assert!(s.is_clear());
    }
}
