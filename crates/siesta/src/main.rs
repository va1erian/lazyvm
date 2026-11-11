//! `siestac`: the Siesta compiler's command line (docs/siesta.md section 9).

use std::process::ExitCode;

use siesta::{Rendered, Source};

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let Some(cmd) = args.first() else {
        eprintln!("usage: siestac check files...");
        return ExitCode::from(2);
    };
    if cmd != "check" {
        eprintln!("siestac: unknown command `{cmd}`");
        return ExitCode::from(2);
    }
    let mut sources = Vec::new();
    for path in &args[1..] {
        match std::fs::read_to_string(path) {
            Ok(text) => sources.push(Source {
                name: path.clone(),
                text,
            }),
            Err(e) => {
                eprintln!("{path}: {e}");
                return ExitCode::from(2);
            }
        }
    }
    let mut failed = false;
    for (i, src) in sources.iter().enumerate() {
        let file = u32::try_from(i).unwrap_or(u32::MAX);
        if let Err(d) = siesta::parse::parse_file(file, &src.text) {
            eprintln!(
                "{}",
                Rendered {
                    diag: &d,
                    sources: &sources
                }
            );
            failed = true;
        }
    }
    if failed {
        ExitCode::from(1)
    } else {
        ExitCode::SUCCESS
    }
}
