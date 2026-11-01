//! The system properties the VM started with: what the launcher sets before boot. Java
//! code cannot change them: once a program does, `java.lang.System` holds its own table
//! on top. So an option read here (`lazy.gfx`) is one given on the command line. Names
//! are Java strings, and these methods are the only way in or out of the map.

use crate::Vm;

impl Vm {
    /// The value of the property `name` (UTF-16 units), if set.
    pub fn property(&self, name: &[u16]) -> Option<&[u16]> {
        self.properties.get(name).map(Vec::as_slice)
    }

    /// Every property, as (name, value).
    pub fn properties(&self) -> impl Iterator<Item = (&[u16], &[u16])> {
        self.properties
            .iter()
            .map(|(k, v)| (k.as_slice(), v.as_slice()))
    }

    /// Sets a property, returning the old value.
    pub fn set_property(&mut self, name: &[u16], value: Vec<u16>) -> Option<Vec<u16>> {
        self.properties.insert(name.to_vec(), value)
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
        assert_eq!(vm.property_text("k"), Some(String::from_utf16_lossy(&[3])));
    }
}
