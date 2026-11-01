//! The verifier on whatever parses, against a made-up class world: any class
//! name exists, and its shape follows from its name, so every path through the
//! assignability and protected checks is reachable without a class library.

#![no_main]

use std::sync::Arc;

use classfile::ClassFile;
use libfuzzer_sys::fuzz_target;
use vm::verify::{ClassInfo, Hierarchy, verify_class};
use vm::{VmError, VmResult};

const OBJECT: &str = "java/lang/Object";

/// `FNV-1a`: a stable hash, so a failing input stays failing.
fn hash(s: &str) -> u32 {
    s.bytes().fold(0x811c_9dc5, |h, b| (h ^ u32::from(b)).wrapping_mul(0x0100_0193))
}

struct World;

impl Hierarchy for World {
    fn info(&mut self, name: &str) -> VmResult<ClassInfo> {
        let h = hash(name);
        // Some names do not load, as on a real class path.
        if h % 31 == 0 {
            return Err(VmError::exception("java/lang/NoClassDefFoundError", name));
        }
        let super_name = (name != OBJECT).then(|| {
            // A short chain of invented names that always reaches `Object`.
            if name.len() < 24 && h % 3 == 0 {
                Arc::from(format!("{name}x"))
            } else {
                Arc::from(OBJECT)
            }
        });
        Ok(ClassInfo {
            is_interface: h % 7 == 0,
            super_name,
            boot: h % 2 == 0,
        })
    }

    fn member_access(
        &mut self,
        class: &str,
        name: &str,
        descriptor: &str,
        _method: bool,
    ) -> VmResult<Option<u16>> {
        let h = hash(class) ^ hash(name) ^ hash(descriptor);
        Ok((h % 2 == 0).then_some((h >> 8) as u16))
    }
}

fuzz_target!(|data: &[u8]| {
    let Ok(cf) = ClassFile::parse(data) else {
        return;
    };
    let _ = vm::verify::structural::check_format(&cf, 65535);
    // Format checks may fail and the checker must still hold: it never trusts them.
    let _ = verify_class(&cf, None, &mut World);
    let _ = verify_class(&cf, Some("java/lang/Host"), &mut World);
});
