//! On Windows the VM runs on the process's main thread, because winit (and
//! so `lazy.ui`, docs/ui.md) only opens windows there. That thread's stack is
//! set by the linker, so reserve what `main.rs` gives a spawned VM thread
//! elsewhere (`VM_STACK_BYTES`, 256 MiB; reserved, not committed).
//! `crates/launcher-ui` builds with this same script, which there also keeps
//! the window stack's OpenGL DLL from loading at start.
fn main() {
    let windows = std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows");
    match std::env::var("CARGO_CFG_TARGET_ENV").as_deref() {
        Ok("msvc") if windows => {
            println!("cargo:rustc-link-arg-bins=/STACK:268435456");
            // `lazyvm-ui` links xui's winit backend, which links
            // opengl32.dll for its optional GL path. Loaded at start, it
            // costs every run ~60 ms of driver setup, so load it only if GL
            // is ever called (docs/ui.md, "Size"). `lazyvm` does not link it.
            if std::env::var("CARGO_PKG_NAME").as_deref() == Ok("launcher-ui") {
                println!("cargo:rustc-link-arg-bins=/DELAYLOAD:opengl32.dll");
                println!("cargo:rustc-link-arg-bins=delayimp.lib");
            }
        }
        Ok("gnu") if windows => println!("cargo:rustc-link-arg-bins=-Wl,--stack,268435456"),
        _ => {}
    }
}
