//! Event requests: what the debugger asked to be told about, and the test
//! that decides whether an event it describes matches one.

use std::collections::HashMap;
use std::hash::{BuildHasherDefault, Hasher};

use super::wire::{Reader, Res, err};
use crate::class::ClassId;

pub const SINGLE_STEP: u8 = 1;
pub const BREAKPOINT: u8 = 2;
pub const CLASS_PREPARE: u8 = 8;
pub const VM_DEATH: u8 = 99;
/// Accepted, and never sent: this VM has nothing to report for them.
const SILENT: [u8; 3] = [6, 7, 9];

pub const SUSPEND_THREAD: u8 = 1;
pub const SUSPEND_ALL: u8 = 2;

pub const STEP_MIN: i32 = 0;
pub const DEPTH_INTO: i32 = 0;
pub const DEPTH_OUT: i32 = 2;

/// Where a step started, and how far it has got.
#[derive(Clone, Copy, Debug)]
pub struct StepState {
    pub thread: usize,
    pub frames: usize,
    /// The line it started on, -1 when the method has none.
    pub line: i32,
    pub min: bool,
    pub depth: i32,
    /// The instruction the thread stopped at has not run yet: it is not a step.
    pub armed: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Modifier {
    Count(i32),
    ThreadOnly(u64),
    ClassOnly(ClassId),
    ClassMatch(String),
    ClassExclude(String),
    Location {
        class: ClassId,
        method: u16,
        index: u64,
    },
    Step {
        thread: u64,
        size: i32,
        depth: i32,
    },
    InstanceOnly(u64),
}

#[derive(Clone, Debug)]
pub struct Request {
    pub id: i32,
    pub kind: u8,
    pub policy: u8,
    pub mods: Vec<Modifier>,
    pub step: Option<StepState>,
}

/// What an event is, for the modifiers to look at.
pub struct Subject<'a> {
    pub thread: u64,
    pub class_name: &'a str,
    /// `class` and everything it extends or implements (for `ClassOnly`).
    pub ancestors: &'a [ClassId],
    /// The `this` of the frame, as an id, 0 for none.
    pub instance: u64,
    /// Where the event happened, for breakpoints: class, method, code index.
    pub location: Option<(ClassId, u16, u64)>,
}

/// A multiplicative hash: the keys are already well spread numbers, and the
/// breakpoint lookup runs before every instruction.
#[derive(Default)]
pub struct KeyHasher(u64);

impl Hasher for KeyHasher {
    fn finish(&self) -> u64 {
        self.0
    }

    fn write(&mut self, bytes: &[u8]) {
        for &b in bytes {
            self.0 = (self.0 ^ u64::from(b)).wrapping_mul(0x100_0000_01b3);
        }
    }

    fn write_u64(&mut self, v: u64) {
        self.0 = v.wrapping_mul(0x9e37_79b9_7f4a_7c15);
    }
}

type Fast = BuildHasherDefault<KeyHasher>;

/// The key of a breakpoint location: class, method and code index.
pub fn location_key(class: ClassId, method: u16, pc: u64) -> u64 {
    (u64::from(class.0) << 32) | (u64::from(method) << 16) | (pc & 0xffff)
}

#[derive(Default)]
pub struct Requests {
    list: Vec<Request>,
    next_id: i32,
    /// Breakpoint locations, so the check before each instruction is one lookup.
    at: HashMap<u64, u32, Fast>,
    steps: usize,
}

impl Requests {
    /// Whether the interpreter must look at every instruction.
    pub fn needs_hooks(&self) -> bool {
        !self.at.is_empty() || self.steps > 0
    }

    pub fn len(&self) -> usize {
        self.list.len()
    }

    pub fn has_step(&self) -> bool {
        self.steps > 0
    }

    pub fn has_breakpoint_at(&self, key: u64) -> bool {
        self.at.contains_key(&key)
    }

    pub fn add(&mut self, mut r: Request) -> i32 {
        self.next_id += 1;
        r.id = self.next_id;
        self.list.push(r);
        self.reindex();
        self.next_id
    }

    pub fn remove(&mut self, kind: u8, id: i32) {
        self.list.retain(|r| !(r.kind == kind && r.id == id));
        self.reindex();
    }

    pub fn clear_kind(&mut self, kind: u8) {
        self.list.retain(|r| r.kind != kind);
        self.reindex();
    }

    pub fn clear(&mut self) {
        self.list.clear();
        self.reindex();
    }

    pub fn steps_of(&self, thread: usize) -> impl Iterator<Item = &Request> {
        self.list
            .iter()
            .filter(move |r| r.step.is_some_and(|s| s.thread == thread))
    }

    pub fn set_step(&mut self, id: i32, state: StepState) {
        if let Some(r) = self.list.iter_mut().find(|r| r.id == id) {
            r.step = Some(state);
        }
    }

    pub fn policy_of(&self, id: i32) -> u8 {
        self.list
            .iter()
            .find(|r| r.id == id)
            .map_or(0, |r| r.policy)
    }

    pub fn first_of(&self, kind: u8) -> Option<i32> {
        self.list.iter().find(|r| r.kind == kind).map(|r| r.id)
    }

    fn reindex(&mut self) {
        self.at.clear();
        for r in &self.list {
            if let (
                BREAKPOINT,
                Some(Modifier::Location {
                    class,
                    method,
                    index,
                }),
            ) = (
                r.kind,
                r.mods
                    .iter()
                    .find(|m| matches!(m, Modifier::Location { .. })),
            ) {
                *self
                    .at
                    .entry(location_key(*class, *method, *index))
                    .or_insert(0) += 1;
            }
        }
        self.steps = self.list.iter().filter(|r| r.step.is_some()).count();
    }

    /// The requests of `kind` whose modifiers accept `s`, as (id, suspend
    /// policy). A request whose count runs out is removed when it fires.
    pub fn hits(&mut self, kind: u8, s: &Subject) -> Vec<(i32, u8)> {
        let mut out = Vec::new();
        let mut spent = Vec::new();
        for r in self.list.iter_mut().filter(|r| r.kind == kind) {
            if !accepts(&r.mods, s) {
                continue;
            }
            if count_reached(&mut r.mods) {
                out.push((r.id, r.policy));
                if r.mods.iter().any(|m| matches!(m, Modifier::Count(_))) {
                    spent.push(r.id);
                }
            }
        }
        for id in spent {
            self.remove(kind, id);
        }
        out
    }

    /// A step that fired is over: the request stays until the debugger clears
    /// it, but nothing is watched for it any more.
    pub fn finish_step(&mut self, id: i32) {
        if let Some(r) = self.list.iter_mut().find(|r| r.id == id) {
            r.step = None;
        }
        self.reindex();
    }

    pub fn modifiers_of(&self, id: i32) -> &[Modifier] {
        self.list
            .iter()
            .find(|r| r.id == id)
            .map_or(&[], |r| &r.mods[..])
    }
}

/// Counts one occurrence down; true when the request fires.
fn count_reached(mods: &mut [Modifier]) -> bool {
    let mut fire = true;
    for m in mods {
        if let Modifier::Count(n) = m {
            *n -= 1;
            fire &= *n <= 0;
        }
    }
    fire
}

fn accepts(mods: &[Modifier], s: &Subject) -> bool {
    mods.iter().all(|m| match m {
        Modifier::ThreadOnly(t) => *t == s.thread,
        Modifier::ClassOnly(c) => s.ancestors.contains(c),
        Modifier::ClassMatch(p) => pattern_matches(p, s.class_name),
        Modifier::ClassExclude(p) => !pattern_matches(p, s.class_name),
        Modifier::InstanceOnly(i) => *i == s.instance,
        Modifier::Location {
            class,
            method,
            index,
        } => s.location == Some((*class, *method, *index)),
        _ => true,
    })
}

/// A class pattern is an exact name, or one that starts or ends with `*`.
pub fn pattern_matches(pattern: &str, name: &str) -> bool {
    if let Some(rest) = pattern.strip_prefix('*') {
        name.ends_with(rest)
    } else if let Some(rest) = pattern.strip_suffix('*') {
        name.starts_with(rest)
    } else {
        pattern == name
    }
}

/// Reads one request modifier from a `Set` command.
pub fn read_modifier(r: &mut Reader) -> Res<Modifier> {
    Ok(match r.u8()? {
        1 => Modifier::Count(r.i32()?),
        3 => Modifier::ThreadOnly(r.id()?),
        4 => Modifier::ClassOnly(super::ids::class_of_type(r.id()?)?),
        5 => Modifier::ClassMatch(r.string()?),
        6 => Modifier::ClassExclude(r.string()?),
        7 => {
            r.u8()?;
            let class = super::ids::class_of_type(r.id()?)?;
            let (_, method) = super::ids::split_member(r.id()?)?;
            let index = r.i64()?;
            let method = u16::try_from(method).map_err(|_| err::INVALID_METHODID)?;
            Modifier::Location {
                class,
                method,
                index: index as u64,
            }
        }
        10 => {
            let thread = r.id()?;
            Modifier::Step {
                thread,
                size: r.i32()?,
                depth: r.i32()?,
            }
        }
        11 => Modifier::InstanceOnly(r.id()?),
        2 | 8 | 9 | 12 => return Err(err::NOT_IMPLEMENTED),
        _ => return Err(err::ILLEGAL_ARGUMENT),
    })
}

/// Whether requests of this kind can be made at all.
pub fn kind_supported(kind: u8) -> Res<()> {
    match kind {
        SINGLE_STEP | BREAKPOINT | CLASS_PREPARE | VM_DEATH => Ok(()),
        k if SILENT.contains(&k) => Ok(()),
        3..=5 | 10 | 20 | 21 | 30 | 40..=46 | 90 => Err(err::NOT_IMPLEMENTED),
        _ => Err(err::INVALID_EVENT_TYPE),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn subject(name: &str) -> Subject<'_> {
        Subject {
            thread: 5,
            class_name: name,
            ancestors: &[ClassId(1)],
            instance: 0,
            location: Some((ClassId(1), 2, 3)),
        }
    }

    #[test]
    fn class_patterns_match_exactly_or_at_one_end() {
        assert!(pattern_matches("a.B", "a.B"));
        assert!(pattern_matches("a.*", "a.B"));
        assert!(pattern_matches("*.B", "a.B"));
        assert!(!pattern_matches("a.B", "a.BC"));
        assert!(!pattern_matches("a.*", "b.B"));
    }

    #[test]
    fn a_count_fires_once_and_the_request_goes() {
        let mut reqs = Requests::default();
        let at = Modifier::Location {
            class: ClassId(1),
            method: 2,
            index: 3,
        };
        let id = reqs.add(Request {
            id: 0,
            kind: BREAKPOINT,
            policy: 2,
            mods: vec![at, Modifier::Count(2)],
            step: None,
        });
        let s = subject("X");
        assert!(reqs.hits(BREAKPOINT, &s).is_empty());
        assert_eq!(reqs.hits(BREAKPOINT, &s), vec![(id, 2)]);
        assert!(reqs.hits(BREAKPOINT, &s).is_empty());
        assert!(!reqs.needs_hooks());
    }

    #[test]
    fn thread_and_class_modifiers_filter() {
        let mut reqs = Requests::default();
        let mods = vec![Modifier::ThreadOnly(9), Modifier::ClassMatch("X*".into())];
        reqs.add(Request {
            id: 0,
            kind: CLASS_PREPARE,
            policy: 0,
            mods,
            step: None,
        });
        assert!(reqs.hits(CLASS_PREPARE, &subject("Xy")).is_empty());
        let mut s = subject("Xy");
        s.thread = 9;
        assert_eq!(reqs.hits(CLASS_PREPARE, &s).len(), 1);
        s.class_name = "Y";
        assert!(reqs.hits(CLASS_PREPARE, &s).is_empty());
    }

    #[test]
    fn hostile_modifiers_are_errors() {
        assert_eq!(
            read_modifier(&mut Reader::new(&[1])),
            Err(err::ILLEGAL_ARGUMENT)
        );
        assert_eq!(
            read_modifier(&mut Reader::new(&[77])),
            Err(err::ILLEGAL_ARGUMENT)
        );
        assert_eq!(
            read_modifier(&mut Reader::new(&[8])),
            Err(err::NOT_IMPLEMENTED)
        );
        assert_eq!(
            read_modifier(&mut Reader::new(&[5, 0xff, 0xff, 0xff, 0xff])),
            Err(err::INVALID_LENGTH)
        );
        assert_eq!(
            read_modifier(&mut Reader::new(&[4, 0, 0, 0, 0, 0, 0, 0, 0])),
            Err(err::INVALID_CLASS)
        );
    }

    #[test]
    fn unknown_event_kinds_are_not_accepted() {
        assert_eq!(kind_supported(BREAKPOINT), Ok(()));
        assert_eq!(kind_supported(4), Err(err::NOT_IMPLEMENTED));
        assert_eq!(kind_supported(200), Err(err::INVALID_EVENT_TYPE));
    }
}
