//! Reading the op list Java encodes widgets into (docs/ui.md, "Building").
//! Every count, index and size is checked: a malformed list is an error,
//! never a panic.

/// Widgets as Java encoded them: ints, plus a table of the strings and
/// arrays they index.
pub struct Tree {
    pub ops: Vec<i32>,
    pub objects: Vec<Obj>,
}

/// One entry of the object table, copied off the Java heap.
#[derive(Clone, Debug, PartialEq)]
pub enum Obj {
    Str(String),
    Ints(Vec<i32>),
}

/// xui forms nest at most 16 deep; Java trees follow the same bound.
pub const MAX_DEPTH: usize = 16;
/// The largest length in design units (xui's `MAX_LENGTH`).
pub const MAX_LENGTH: i32 = 32768;
/// The most items a list-like widget takes from one op list.
const MAX_ITEMS: i32 = 1 << 20;

pub struct Reader<'a> {
    tree: &'a Tree,
    pos: usize,
}

impl<'a> Reader<'a> {
    pub fn new(tree: &'a Tree) -> Reader<'a> {
        Reader { tree, pos: 0 }
    }

    /// How many ints are left.
    pub fn remaining(&self) -> usize {
        self.tree.ops.len().saturating_sub(self.pos)
    }

    pub fn done(&self) -> bool {
        self.pos == self.tree.ops.len()
    }

    pub fn peek(&self) -> Result<i32, String> {
        self.tree
            .ops
            .get(self.pos)
            .copied()
            .ok_or_else(|| "op list ends early".to_string())
    }

    pub fn int(&mut self) -> Result<i32, String> {
        let v = self.peek()?;
        self.pos += 1;
        Ok(v)
    }

    pub fn flag(&mut self) -> Result<bool, String> {
        Ok(self.int()? != 0)
    }

    /// A length in design units, or -1 for "not set".
    pub fn opt_length(&mut self) -> Result<Option<i32>, String> {
        match self.int()? {
            -1 => Ok(None),
            v if (0..=MAX_LENGTH).contains(&v) => Ok(Some(v)),
            v => Err(format!("length {v} out of range")),
        }
    }

    pub fn length(&mut self) -> Result<i32, String> {
        self.opt_length()?
            .ok_or_else(|| "length missing".to_string())
    }

    /// A count of things that each take at least `each` more ints.
    pub fn count(&mut self, each: usize) -> Result<usize, String> {
        let n = self.int()?;
        let left = self.tree.ops.len().saturating_sub(self.pos) / each.max(1);
        usize::try_from(n)
            .ok()
            .filter(|&n| n <= left && n <= MAX_ITEMS as usize)
            .ok_or_else(|| format!("bad count {n}"))
    }

    pub fn string(&mut self) -> Result<String, String> {
        self.opt_string()?
            .ok_or_else(|| "string missing".to_string())
    }

    /// A string index, or -1 for none.
    pub fn opt_string(&mut self) -> Result<Option<String>, String> {
        let i = self.int()?;
        if i == -1 {
            return Ok(None);
        }
        match usize::try_from(i).ok().and_then(|i| self.tree.objects.get(i)) {
            Some(Obj::Str(s)) => Ok(Some(s.clone())),
            _ => Err(format!("string index {i} out of range")),
        }
    }

    /// An `int[]` from the object table.
    pub fn ints(&mut self) -> Result<Vec<i32>, String> {
        let i = self.int()?;
        match usize::try_from(i).ok().and_then(|i| self.tree.objects.get(i)) {
            Some(Obj::Ints(v)) => Ok(v.clone()),
            _ => Err(format!("array index {i} out of range")),
        }
    }

    pub fn strings(&mut self) -> Result<Vec<String>, String> {
        let n = self.count(1)?;
        (0..n).map(|_| self.string()).collect()
    }

}

#[cfg(test)]
mod tests {
    use super::*;

    fn tree(ops: &[i32]) -> Tree {
        Tree {
            ops: ops.to_vec(),
            objects: vec![Obj::Str("a".into())],
        }
    }

    #[test]
    fn reads_stop_at_the_end() {
        let t = tree(&[1]);
        let mut r = Reader::new(&t);
        assert_eq!(r.int(), Ok(1));
        assert!(r.int().is_err());
        assert!(r.done());
    }

    #[test]
    fn counts_cannot_exceed_what_is_left() {
        let t = tree(&[3, 0, 0]);
        assert!(Reader::new(&t).count(1).is_err());
        let t = tree(&[2, 0, 0]);
        assert_eq!(Reader::new(&t).count(1), Ok(2));
        let t = tree(&[-1]);
        assert!(Reader::new(&t).count(1).is_err());
    }

    #[test]
    fn strings_and_lengths_are_checked() {
        let t = tree(&[0, -1, 1, 40000, -2]);
        let mut r = Reader::new(&t);
        assert_eq!(r.string(), Ok("a".into()));
        assert_eq!(r.opt_string(), Ok(None));
        assert!(r.string().is_err());
        assert!(r.opt_length().is_err());
        assert!(r.opt_length().is_err());
    }
}
