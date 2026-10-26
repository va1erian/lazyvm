//! Link-time checks that need classes loaded: Java 8 refuses a call site
//! whose argument types cannot reach the implementation, or whose boxing
//! targets a type the wrapper does not extend (observed on Temurin 8; the
//! `LambdaMetafactory` Javadoc states the same rules).

use classfile::descriptor::{FieldType, parse_method};

use super::Spec;
use super::spin::{impl_params, impl_result};
use crate::class::ClassId;
use crate::{Vm, VmResult};

/// The class standing for a type in these checks: a primitive stands for
/// its wrapper, since only boxed primitives are compared with references.
fn wrapper_name(t: &FieldType) -> Option<&'static str> {
    Some(match t {
        FieldType::Boolean => "java/lang/Boolean",
        FieldType::Byte => "java/lang/Byte",
        FieldType::Char => "java/lang/Character",
        FieldType::Short => "java/lang/Short",
        FieldType::Int => "java/lang/Integer",
        FieldType::Long => "java/lang/Long",
        FieldType::Float => "java/lang/Float",
        FieldType::Double => "java/lang/Double",
        _ => return None,
    })
}

impl Vm {
    /// `Ok(Err(reason))` when the call site must not link; `Err` when a
    /// class it names cannot be loaded.
    pub(super) fn check_lambda_types(&mut self, spec: &Spec) -> VmResult<Result<(), String>> {
        let target = match parse_method(&spec.impl_ref.descriptor) {
            Ok(t) => t,
            Err(e) => return Ok(Err(e)),
        };
        let params = impl_params(spec, &target);
        let n = spec.captured.len();
        for (j, t) in spec.instantiated.params.iter().enumerate() {
            let Some(a) = params.get(n + j) else { continue };
            // Reference to reference must be assignable; a boxed primitive's
            // wrapper must be assignable to its target.
            if a.is_reference() && !self.type_assignable(t, a)? {
                return Ok(Err(format!(
                    "argument type {} cannot be passed as {}",
                    t.descriptor(),
                    a.descriptor()
                )));
            }
        }
        if let (Some(produced), Some(wanted)) = (impl_result(spec, &target), &spec.instantiated.ret)
            && wrapper_name(&produced).is_some()
            && wanted.is_reference()
            && !self.type_assignable(&produced, wanted)?
        {
            return Ok(Err(format!(
                "result type {} cannot be returned as {}",
                produced.descriptor(),
                wanted.descriptor()
            )));
        }
        Ok(Ok(()))
    }

    fn type_class(&mut self, t: &FieldType) -> VmResult<ClassId> {
        match (t, wrapper_name(t)) {
            (_, Some(w)) => self.load_class(w),
            (FieldType::Object(name), None) => self.load_class(name),
            (other, None) => self.load_class(&other.descriptor()),
        }
    }

    fn type_assignable(&mut self, from: &FieldType, to: &FieldType) -> VmResult<bool> {
        let (f, t) = (self.type_class(from)?, self.type_class(to)?);
        Ok(self.is_assignable(f, t))
    }
}
