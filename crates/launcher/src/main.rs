//! The `lazyvm` command line: `lazyvm [options] <main class> [args...]`.
//!
//! It accepts the options IntelliJ and the `java` launcher pass
//! (docs/decisions.md D3) and ignores the IDE-only ones.

mod options;

use std::process::ExitCode;

use options::{Command, Launch};
use vm::{ClassPath, Value, Vm, VmError};

/// The exit status for an internal VM failure, as in `sysexits.h`.
const EXIT_INTERNAL: u8 = 70;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let launch = match options::parse(&args) {
        Ok(Command::Run(launch)) => launch,
        Ok(Command::Version) => {
            println!(
                "lazyvm {} (runs Java 8 bytecode)",
                env!("CARGO_PKG_VERSION")
            );
            return ExitCode::SUCCESS;
        }
        Ok(Command::Help) => {
            println!("{}", options::USAGE);
            return ExitCode::SUCCESS;
        }
        Err(msg) => {
            eprintln!("lazyvm: {msg}\n{}", options::USAGE);
            return ExitCode::from(2);
        }
    };
    let code = run(launch);
    let _ = platform::flush(platform::Stream::Out);
    let _ = platform::flush(platform::Stream::Err);
    code
}

fn run(launch: Launch) -> ExitCode {
    let classpath = ClassPath::from_strings(&launch.boot_classpath, &launch.classpath);
    let mut vm = Vm::new(classpath, launch.options.clone());
    natives::register_all(&mut vm);
    vm.properties.extend(platform::default_properties());
    let joined = std::env::join_paths(&launch.classpath).map(|p| p.to_string_lossy().into_owned());
    vm.properties
        .insert("java.class.path".into(), joined.unwrap_or_default());
    vm.properties.extend(launch.properties.clone());
    if let Err(e) = vm.boot() {
        eprintln!("lazyvm: cannot start: the class library is missing or broken ({e})");
        eprintln!("  boot class path: {:?}", launch.boot_classpath);
        return ExitCode::from(1);
    }
    let result = run_main(&mut vm, &launch.main_class, &launch.args);
    finish(&mut vm, result)
}

/// Loads the main class and calls `public static void main(String[])`.
fn run_main(vm: &mut Vm, main_class: &str, args: &[String]) -> Result<(), VmError> {
    let internal = main_class.replace('.', "/");
    let class = match vm.load_class(&internal) {
        Ok(c) => c,
        Err(VmError::Pending {
            class: "java/lang/NoClassDefFoundError",
            ..
        }) => {
            eprintln!("Error: Could not find or load main class {main_class}");
            return Err(VmError::Exit(1));
        }
        Err(e) => return Err(e),
    };
    let main = vm
        .find_in_class(class, "main", "([Ljava/lang/String;)V", false)
        .filter(|&m| vm.method(m).is_static() && vm.method(m).access & 0x0001 != 0);
    let Some(main) = main else {
        eprintln!(
            "Error: Main method not found in class {main_class}, please define the main method as:"
        );
        eprintln!("   public static void main(String[] args)");
        return Err(VmError::Exit(1));
    };
    vm.initialize(class)?;
    let len = i32::try_from(args.len()).map_err(|_| VmError::OutOfMemory)?;
    let array = vm.new_array_of("Ljava/lang/String;", len)?;
    for (i, a) in args.iter().enumerate() {
        let s = vm.new_string(a)?;
        vm.heap
            .array_mut(array)?
            .store(i, Value::Ref(Some(s)), false)?;
    }
    vm.invoke(main, &[Value::Ref(Some(array))])?;
    Ok(())
}

/// Turns the outcome of `main` into an exit status, printing an uncaught
/// exception the way `ThreadGroup.uncaughtException` does.
fn finish(vm: &mut Vm, result: Result<(), VmError>) -> ExitCode {
    let err = match result {
        Ok(()) => return ExitCode::SUCCESS,
        Err(VmError::Exit(code)) => return ExitCode::from(code as u8),
        Err(VmError::Internal(msg)) => return internal(&msg),
        Err(e) => e,
    };
    let ex = match vm.materialize(err) {
        Ok(ex) => ex,
        Err(VmError::Exit(code)) => return ExitCode::from(code as u8),
        Err(e) => return internal(&e.to_string()),
    };
    match report_uncaught(vm, ex) {
        Ok(()) | Err(VmError::Throw(_)) => ExitCode::from(1),
        Err(VmError::Exit(code)) => ExitCode::from(code as u8),
        Err(e) => internal(&e.to_string()),
    }
}

fn report_uncaught(vm: &mut Vm, ex: vm::Ref) -> Result<(), VmError> {
    let class = vm.load_class("lazy/internal/VM")?;
    vm.initialize(class)?;
    let uncaught = vm
        .find_in_class(class, "uncaught", "(Ljava/lang/Throwable;)V", false)
        .ok_or_else(|| VmError::Internal("lazy.internal.VM.uncaught is missing".into()))?;
    vm.invoke(uncaught, &[Value::Ref(Some(ex))])?;
    Ok(())
}

fn internal(msg: &str) -> ExitCode {
    eprintln!("lazyvm: internal error: {msg}");
    ExitCode::from(EXIT_INTERNAL)
}
