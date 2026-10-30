//! `lazyvm`: the VM without `lazy.ui`, so without xui (docs/ui.md, "Size").

use std::process::ExitCode;

fn main() -> ExitCode {
    launcher::main(&[])
}
