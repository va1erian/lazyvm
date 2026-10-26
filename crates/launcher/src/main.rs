//! The `lazyvm` command line: `lazyvm [options] <main class> [args...]`.
//!
//! It accepts the options IntelliJ and the `java` launcher pass
//! (docs/decisions.md D3) and ignores the IDE-only ones.

mod jarfile;
mod options;

use std::process::ExitCode;

use options::{Command, Launch};
use vm::{ClassPath, Value, Vm, VmError};

/// The exit status for an internal VM failure, as in `sysexits.h`.
const EXIT_INTERNAL: u8 = 70;
/// Reserved, not committed, by the OS until used.
const VM_STACK_BYTES: usize = 256 << 20;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let launch = match options::parse(&args) {
        Ok(Command::Run(launch)) => launch,
        Ok(Command::Version) => {
            // On stderr, in the `<name> version "<version>"` shape IDEs parse.
            let v = env!("CARGO_PKG_VERSION");
            eprintln!("lazyvm version \"1.8.0\"");
            eprintln!("LazyVM Runtime Environment (build {v})");
            eprintln!("LazyVM (build {v}, interpreted mode, runs Java 8 bytecode)");
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
    // Loading, initialization and calls from natives recurse on the Rust
    // stack (bounded by `Options::max_nesting`); give the VM room for that.
    let vm_thread = std::thread::Builder::new()
        .name("main".into())
        .stack_size(VM_STACK_BYTES)
        .spawn(move || run(launch));
    let code = match vm_thread.map(|t| t.join()) {
        Ok(Ok(code)) => code,
        Ok(Err(_)) => internal("the VM thread panicked"),
        Err(e) => internal(&format!("cannot start the VM thread: {e}")),
    };
    let _ = platform::flush(platform::Stream::Out);
    let _ = platform::flush(platform::Stream::Err);
    code
}

fn run(mut launch: Launch) -> ExitCode {
    if let Some(jar) = &launch.jar {
        match jarfile::resolve(jar) {
            Ok(j) => {
                launch.main_class = Some(j.main_class);
                launch.classpath = j.classpath;
            }
            Err(msg) => {
                eprintln!("{msg}");
                return ExitCode::from(1);
            }
        }
    }
    let classpath = match ClassPath::open(&launch.boot_classpath, &launch.classpath) {
        Ok(cp) => cp,
        Err(e) => {
            eprintln!("lazyvm: bad class path entry: {e}");
            return ExitCode::from(1);
        }
    };
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
    let main_class = launch.main_class.clone().unwrap_or_default();
    let result = run_main(&mut vm, &main_class, &launch.args);
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
