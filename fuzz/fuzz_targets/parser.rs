//! The class file parser and the format checks: any bytes give a class or an
//! error, never a panic. A class that parses also goes through the accessors
//! the VM calls on it. Jars take the same input, since they parse untrusted
//! offsets of their own.

#![no_main]

use classfile::ClassFile;
use classfile::jar::{Jar, manifest_attributes};
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    if let Ok(cf) = ClassFile::parse(data) {
        let _ = vm::verify::structural::check_format(&cf, 65535);
        let _ = cf.inner_class_flags();
        let _ = cf.outer_class();
        for i in 0..cf.pool.len() {
            let index = i as u16;
            let _ = cf.pool.member_ref(index);
            let _ = cf.pool.class_name(index);
            let _ = cf.pool.utf16(index);
        }
    }
    if let Ok(jar) = Jar::open(data.to_vec()) {
        for name in jar.names().to_vec() {
            if let Ok(Some(bytes)) = jar.read(&name) {
                let _ = manifest_attributes(&String::from_utf8_lossy(&bytes));
            }
        }
    }
});
