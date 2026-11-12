//! `lazyvm-ui`: `lazyvm` with `lazy.ui` on xui (docs/ui.md).

use std::process::ExitCode;

use natives_ui::UiHost;

fn main() -> ExitCode {
    launcher::main(&[|vm| natives_ui::register(vm, HOST)])
}

/// The window host this build has: the winit window on Windows, none
/// elsewhere (headless only; LazyOS builds its own binary, crates/lazyvm-lazyos).
const HOST: UiHost = UiHost {
    desktop: xui_host::desktop,
    error_box: xui_host::error_box,
};

mod xui_host {
    use natives_ui::Host;

    #[cfg(all(windows, feature = "desktop"))]
    pub fn desktop() -> Result<Host, String> {
        Ok(Host::Desktop(lazyvm_win::ui_backend()))
    }

    #[cfg(all(windows, feature = "desktop"))]
    pub fn error_box(title: &str, text: &str) {
        lazyvm_win::error_box(title, text);
    }

    #[cfg(not(all(windows, feature = "desktop")))]
    pub fn desktop() -> Result<Host, String> {
        Err("no window host in this build; use -Dlazy.ui=headless".into())
    }

    /// No window host, so no window was open: nothing to show.
    #[cfg(not(all(windows, feature = "desktop")))]
    pub fn error_box(_title: &str, _text: &str) {}
}
