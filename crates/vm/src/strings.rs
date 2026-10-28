//! Java strings: creating them from Rust, reading them back, interning.
//! A `java.lang.String` is an instance whose `value` field is a `char[]`
//! (docs/design/natives.md, VM-known fields).

use crate::heap::Array;
use crate::value::{Ref, Value};
use crate::{Vm, VmError, VmResult};

impl Vm {
    pub fn new_string(&mut self, s: &str) -> VmResult<Ref> {
        let units: Vec<u16> = s.encode_utf16().collect();
        self.new_string_utf16(&units)
    }

    pub fn new_string_utf16(&mut self, units: &[u16]) -> VmResult<Ref> {
        let (string, chars) = match (self.wk.string, self.wk.char_array) {
            (Some(s), Some(c)) => (s, c),
            _ => return Err(VmError::Internal("string before boot".into())),
        };
        let len = i32::try_from(units.len()).map_err(|_| VmError::OutOfMemory)?;
        let array = self.new_array(chars, len)?;
        if let Array::Char(a) = self.heap.array_mut(array)? {
            a.copy_from_slice(units);
        }
        let r = self.new_object(string)?;
        self.set_field(r, self.wk.string_value, Value::Ref(Some(array)))?;
        Ok(r)
    }

    /// The UTF-16 contents of a string.
    pub fn string_utf16(&self, s: Ref) -> VmResult<Vec<u16>> {
        let value = self.get_field(s, self.wk.string_value)?.reference()?;
        let array = value.ok_or_else(|| VmError::Internal("string without value".into()))?;
        match self.heap.array(array)? {
            Array::Char(a) => Ok(a.to_vec()),
            _ => Err(VmError::Internal("string value is not char[]".into())),
        }
    }

    /// The contents as Rust text; unpaired surrogates become U+FFFD.
    pub fn string_to_rust(&self, s: Ref) -> VmResult<String> {
        let units = self.string_utf16(s)?;
        Ok(char::decode_utf16(units)
            .map(|c| c.unwrap_or(char::REPLACEMENT_CHARACTER))
            .collect())
    }

    /// A string argument that must not be null.
    pub fn string_arg(&self, v: Value) -> VmResult<String> {
        let r = v.reference()?.ok_or_else(VmError::npe)?;
        self.string_to_rust(r)
    }

    /// A path argument that must not be null, exactly as Java holds it: a
    /// lone surrogate stays itself (see `wtf`), so distinct strings never
    /// name the same file.
    pub fn path_arg(&self, v: Value) -> VmResult<std::path::PathBuf> {
        let r = v.reference()?.ok_or_else(VmError::npe)?;
        Ok(crate::wtf::from_units(&self.string_utf16(r)?).into())
    }

    /// A string for a name the OS gave, the inverse of `path_arg`.
    pub fn new_string_os(&mut self, s: &std::ffi::OsStr) -> VmResult<Ref> {
        self.new_string_utf16(&crate::wtf::to_units(s))
    }

    /// The canonical string with these contents (`String.intern`).
    pub fn intern(&mut self, s: Ref) -> VmResult<Ref> {
        let units = self.string_utf16(s)?;
        if let Some(&r) = self.interned.get(units.as_slice()) {
            return Ok(r);
        }
        self.interned.insert(units.into_boxed_slice(), s);
        Ok(s)
    }

    /// The interned string for literal contents, creating it if needed.
    pub fn intern_utf16(&mut self, units: &[u16]) -> VmResult<Ref> {
        if let Some(&r) = self.interned.get(units) {
            return Ok(r);
        }
        let r = self.new_string_utf16(units)?;
        self.interned.insert(units.into(), r);
        Ok(r)
    }
}
