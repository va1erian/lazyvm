//! Executable memory for compiled methods, W^X: code is copied into pages
//! mapped read-write, which are then made read-execute and never written
//! again. Compiled code lives as long as its method, that is as long as the
//! process (classes are never unloaded), so pages are never freed.

/// Read-execute pages holding one method's code.
pub(super) struct Code {
    ptr: *const u8,
    len: usize,
}

// SAFETY: the pages are read-only after `install` and never freed, so a
// pointer to them may be shared and moved between threads.
unsafe impl Send for Code {}
// SAFETY: as above; nothing writes through the pointer.
unsafe impl Sync for Code {}

impl Code {
    /// Copies `bytes` into fresh pages and makes them executable. None if the
    /// system refuses (the method then stays interpreted).
    pub(super) fn install(bytes: &[u8]) -> Option<Code> {
        if bytes.is_empty() {
            return None;
        }
        let len = bytes.len().div_ceil(PAGE) * PAGE;
        let ptr = sys::map_rw(len)?;
        // SAFETY: `ptr` is a fresh read-write mapping of `len >= bytes.len()`
        // bytes that nothing else refers to.
        unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), ptr, bytes.len()) };
        if !sys::make_rx(ptr, len) {
            return None;
        }
        Some(Code { ptr, len })
    }

    /// The address `offset` bytes into the code.
    pub(super) fn at(&self, offset: usize) -> *const u8 {
        debug_assert!(offset < self.len);
        self.ptr.wrapping_add(offset)
    }
}

/// Pages are 4 KiB on every platform LazyVM runs on (x86-64).
const PAGE: usize = 4096;

#[cfg(windows)]
mod sys {
    use windows_sys::Win32::System::Memory::{
        MEM_COMMIT, MEM_RESERVE, PAGE_EXECUTE_READ, PAGE_READWRITE, VirtualAlloc, VirtualProtect,
    };

    pub(super) fn map_rw(len: usize) -> Option<*mut u8> {
        // SAFETY: a fresh anonymous mapping; no existing memory is touched.
        let p = unsafe {
            VirtualAlloc(
                std::ptr::null(),
                len,
                MEM_COMMIT | MEM_RESERVE,
                PAGE_READWRITE,
            )
        };
        (!p.is_null()).then_some(p.cast())
    }

    pub(super) fn make_rx(ptr: *mut u8, len: usize) -> bool {
        let mut old = 0;
        // SAFETY: `ptr..ptr+len` is the mapping `map_rw` made, fully written.
        unsafe { VirtualProtect(ptr.cast(), len, PAGE_EXECUTE_READ, &mut old) != 0 }
    }
}

#[cfg(unix)]
mod sys {
    pub(super) fn map_rw(len: usize) -> Option<*mut u8> {
        // SAFETY: a fresh anonymous private mapping; no existing memory is touched.
        let p = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                len,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_PRIVATE | libc::MAP_ANONYMOUS,
                -1,
                0,
            )
        };
        (p != libc::MAP_FAILED).then_some(p.cast())
    }

    pub(super) fn make_rx(ptr: *mut u8, len: usize) -> bool {
        // SAFETY: `ptr..ptr+len` is the mapping `map_rw` made, fully written.
        unsafe { libc::mprotect(ptr.cast(), len, libc::PROT_READ | libc::PROT_EXEC) == 0 }
    }
}
