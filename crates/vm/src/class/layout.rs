//! Turning a parsed class file into a runtime class: field slots, the
//! instance template and the method table (JVMS 5.4.2, preparation).

use std::sync::Arc;

use classfile::{ACC_STATIC, ClassFile, descriptor};

use super::{Class, ClassId, ClassKind, Field, InitState, Method, load::check_class_flags};
use crate::value::{Value, default_for};
use crate::{VmError, VmResult};

pub(super) fn build(
    id: ClassId,
    cf: ClassFile,
    super_class: Option<ClassId>,
    interfaces: Vec<ClassId>,
    super_template: Option<&[Value]>,
) -> VmResult<Class> {
    check_class_flags(cf.access).map_err(|e| {
        VmError::exception(
            "java/lang/ClassFormatError",
            format!("{}: {e}", cf.this_class),
        )
    })?;
    let mut template: Vec<Value> = super_template.map(<[Value]>::to_vec).unwrap_or_default();
    let mut statics = Vec::new();
    let mut fields = Vec::with_capacity(cf.fields.len());
    for f in &cf.fields {
        let default = default_for(&f.descriptor);
        let slot = if f.access & ACC_STATIC != 0 {
            statics.push(default);
            statics.len() - 1
        } else {
            template.push(default);
            template.len() - 1
        };
        fields.push(Field {
            name: f.name.clone(),
            descriptor: f.descriptor.clone(),
            access: f.access,
            slot: u32::try_from(slot).map_err(|_| VmError::Internal("too many fields".into()))?,
            constant_value: f.constant_value,
        });
    }
    let methods = cf
        .methods
        .iter()
        .map(build_method)
        .collect::<VmResult<Vec<_>>>()?;
    let cp_len = cf.pool.len();
    Ok(Class {
        id,
        name: cf.this_class.clone(),
        kind: ClassKind::Instance,
        access: cf.access,
        super_class,
        interfaces,
        file: Some(Arc::new(cf)),
        fields,
        methods,
        instance_template: template.into(),
        statics,
        state: InitState::Linked,
        mirror: None,
        cp_cache: vec![None; cp_len],
    })
}

fn build_method(m: &classfile::Member) -> VmResult<Method> {
    // The parser already validated the descriptor; this cannot fail.
    let md = descriptor::parse_method(&m.descriptor).map_err(VmError::Internal)?;
    let this = u16::from(m.access & ACC_STATIC == 0);
    let params = u16::try_from(md.params.len()).map_err(|_| VmError::Internal("arity".into()))?;
    Ok(Method {
        name: m.name.clone(),
        descriptor: m.descriptor.clone(),
        access: m.access,
        code: m.code.clone().map(Arc::new),
        arg_slots: md.arg_slots() + this,
        arg_values: params + this,
        ret: m
            .descriptor
            .rsplit(')')
            .next()
            .and_then(|r| r.bytes().next())
            .unwrap_or(b'V'),
        native: None,
    })
}
