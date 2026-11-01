//! Structural checks: everything that can be judged from one class file alone.
//! `format` is what JVMS 4.8 asks at load time, `code` the static constraints
//! of JVMS 4.9.1 that verification starts from.

mod code;
mod format;

pub(crate) use code::{Layout, check_code};
pub use format::{check_format, is_static_method};

#[cfg(test)]
mod tests;
