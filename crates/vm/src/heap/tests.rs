use super::*;

fn object(fields: Vec<Value>) -> Object {
    Object {
        class: ClassId(0),
        body: Body::Fields(fields.into()),
    }
}

fn alloc(heap: &mut Heap, fields: Vec<Value>) -> Ref {
    match heap.alloc(object(fields)) {
        Ok(r) => r,
        Err(e) => panic!("alloc failed: {e:?}"),
    }
}

#[test]
fn collect_frees_unreachable_and_keeps_reachable() {
    let mut heap = Heap::new(64 << 20);
    let leaf = alloc(&mut heap, vec![Value::Int(1)]);
    let root = alloc(&mut heap, vec![Value::Ref(Some(leaf))]);
    let garbage = alloc(&mut heap, vec![]);
    assert_eq!(heap.collect([root]), 1);
    assert!(heap.get(leaf).is_ok());
    assert!(
        heap.get(garbage).is_err(),
        "freed object must not be reachable"
    );
}

#[test]
fn reused_slot_does_not_alias_a_stale_ref() {
    let mut heap = Heap::new(64 << 20);
    let old = alloc(&mut heap, vec![]);
    heap.collect([]);
    let new = alloc(&mut heap, vec![]);
    assert_eq!(old.index(), new.index(), "the slot is reused");
    assert!(heap.get(old).is_err());
    assert!(heap.get(new).is_ok());
}

#[test]
fn cycles_are_collected() {
    let mut heap = Heap::new(64 << 20);
    let a = alloc(&mut heap, vec![Value::NULL]);
    let b = alloc(&mut heap, vec![Value::Ref(Some(a))]);
    if let Ok(fields) = heap.fields_mut(a) {
        fields[0] = Value::Ref(Some(b));
    }
    assert_eq!(heap.collect([]), 2);
    assert_eq!(heap.object_count(), 0);
}

#[test]
fn ref_arrays_are_traced() {
    let mut heap = Heap::new(64 << 20);
    let item = alloc(&mut heap, vec![]);
    let mut arr = Array::new(array::ElemKind::Ref, 3);
    let _ = arr.store(1, Value::Ref(Some(item)), false);
    let arr = heap.alloc(Object {
        class: ClassId(0),
        body: Body::Array(arr),
    });
    let arr = arr.unwrap_or_else(|e| panic!("{e:?}"));
    assert_eq!(heap.collect([arr]), 0);
    assert!(heap.get(item).is_ok());
}

#[test]
fn stress_many_generations() {
    let mut heap = Heap::new(64 << 20);
    let keep = alloc(&mut heap, vec![]);
    for round in 0..100 {
        for _ in 0..1000 {
            alloc(&mut heap, vec![Value::Int(round)]);
        }
        heap.collect([keep]);
        assert_eq!(heap.object_count(), 1);
    }
    assert!(heap.get(keep).is_ok());
}

#[test]
fn alloc_past_twice_the_limit_fails() {
    let mut heap = Heap::new(1024);
    let big = Object {
        class: ClassId(0),
        body: Body::Array(Array::new(array::ElemKind::Long, 1000)),
    };
    assert!(matches!(heap.alloc(big), Err(VmError::OutOfMemory)));
}
