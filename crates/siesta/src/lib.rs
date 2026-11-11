//! Siesta: a small language with Java's syntax, compiled to version 52
//! class files for LazyVM ([`docs/siesta.md`]).
//!
//! [`docs/siesta.md`]: https://github.com/va1erian/lazyvm/blob/main/docs/siesta.md

pub mod ast;
pub mod classpath;
pub mod declare;
pub mod diag;
pub mod lex;
pub mod parse;
pub mod resolve;
pub mod sig;
pub mod types;
pub mod world;

pub use diag::{Diag, Rendered, Source, Span};
