//! Root enumeration for the collector. Collection only happens at a safe
//! point (an instruction boundary, or before an allocating instruction),
//! where every live reference is reachable from one of these roots.

use crate::Vm;
use crate::class::Resolved;
use crate::value::{Ref, Value};

impl Vm {
    pub fn collect_garbage(&mut self) {
        let mut roots: Vec<Ref> = Vec::new();
        let refs = |vals: &[Value], out: &mut Vec<Ref>| {
            out.extend(vals.iter().filter_map(|v| match v {
                Value::Ref(Some(r)) => Some(*r),
                _ => None,
            }));
        };
        for f in &self.frames {
            refs(&f.locals, &mut roots);
            refs(&f.stack, &mut roots);
        }
        for c in &self.classes {
            refs(&c.statics, &mut roots);
            roots.extend(c.mirror);
            roots.extend(c.cp_cache.iter().filter_map(|r| match r {
                Some(Resolved::String(s)) => Some(*s),
                _ => None,
            }));
        }
        roots.extend(self.interned.values().copied());
        roots.extend(self.roots.iter().copied());
        self.heap.collect(roots);
    }
}
