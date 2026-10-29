//! System properties: names are Java strings, stored under `wtf::escape` so that every
//! distinct name (lone surrogates included) has its own entry. These methods are the
//! only way in or out of the map.

use crate::Vm;
use crate::wtf;

impl Vm {
    /// The value of the property `name` (UTF-16 units), if set.
    pub fn property(&self, name: &[u16]) -> Option<&[u16]> {
        self.properties.get(&wtf::escape(name)).map(Vec::as_slice)
    }

    /// Sets a property, returning the old value.
    pub fn set_property(&mut self, name: &[u16], value: Vec<u16>) -> Option<Vec<u16>> {
        self.properties.insert(wtf::escape(name), value)
    }

    /// Removes a property, returning the old value.
    pub fn clear_property(&mut self, name: &[u16]) -> Option<Vec<u16>> {
        self.properties.remove(&wtf::escape(name))
    }

    /// A property named by text, read as text (lossily, for options such as `lazy.gfx`).
    pub fn property_text(&self, name: &str) -> Option<String> {
        let units: Vec<u16> = name.encode_utf16().collect();
        self.property(&units).map(String::from_utf16_lossy)
    }
}

#[cfg(test)]
mod tests {
    use crate::{ClassPath, Options, Vm};

    #[test]
    fn names_that_differ_stay_apart() {
        let mut vm = Vm::new(ClassPath::default(), Options::default());
        let names: [&[u16]; 4] = [&[0xD800], &[0xDBFF], &[0x6B, 0xDBFF, 0xDFFF], &[0x6B]];
        for (i, n) in names.iter().enumerate() {
            assert_eq!(vm.set_property(n, vec![i as u16]), None);
        }
        for (i, n) in names.iter().enumerate() {
            assert_eq!(vm.property(n), Some(&[i as u16][..]));
        }
        assert_eq!(vm.clear_property(names[0]), Some(vec![0]));
        assert_eq!(vm.property(names[0]), None);
        assert_eq!(vm.property(names[1]), Some(&[1u16][..]));
        assert_eq!(vm.property_text("k"), Some(String::from_utf16_lossy(&[3])));
    }
}
