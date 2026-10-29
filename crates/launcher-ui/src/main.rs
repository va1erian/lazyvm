//! `lazyvm-ui`: `lazyvm` with `lazy.ui` on xui (docs/ui.md).

use std::process::ExitCode;

fn main() -> ExitCode {
    launcher::main(&[natives_ui::register])
}
