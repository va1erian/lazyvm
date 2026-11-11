//! `lazyvm-ui`: `lazyvm` with `lazy.ui` on xui (docs/ui.md).

use std::process::ExitCode;

use xui_host::desktop;

fn main() -> ExitCode {
    launcher::main(&[|vm| natives_ui::register(vm, desktop)])
}

/// The window host this build has: the winit window on Windows, none
/// elsewhere (headless only; LazyOS builds its own binary).
mod xui_host {
    use natives_ui::Host;

    #[cfg(all(windows, feature = "desktop"))]
    pub fn desktop() -> Result<Host, String> {
        Ok(Host::Desktop(lazyvm_win::ui_backend()))
    }

    #[cfg(not(all(windows, feature = "desktop")))]
    pub fn desktop() -> Result<Host, String> {
        Err("no window host in this build; use -Dlazy.ui=headless".into())
    }
}
