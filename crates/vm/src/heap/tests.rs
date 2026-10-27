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

#[test]
fn weak_referents_are_cleared_and_soft_ones_kept_until_asked() {
    let mut heap = Heap::new(64 << 20);
    // Class 1 is a weak reference class, class 2 a soft one; slot 0 is the referent.
    let classes = [None, Some((0u32, false)), Some((0u32, true))];
    let target = alloc(&mut heap, vec![]);
    let weak = heap
        .alloc(Object {
            class: ClassId(1),
            body: Body::Fields(vec![Value::Ref(Some(target))].into()),
        })
        .unwrap_or_else(|e| panic!("{e:?}"));
    let soft_target = alloc(&mut heap, vec![]);
    let soft = heap
        .alloc(Object {
            class: ClassId(2),
            body: Body::Fields(vec![Value::Ref(Some(soft_target))].into()),
        })
        .unwrap_or_else(|e| panic!("{e:?}"));
    let info = WeakInfo {
        classes: &classes,
        clear_soft: false,
    };
    let (_, cleared) = heap.collect_weak([weak, soft], Some(&info));
    assert_eq!(cleared, vec![weak]);
    assert!(heap.get(target).is_err(), "the weak referent is freed");
    assert_eq!(heap.fields(weak).ok().map(|f| f[0]), Some(Value::NULL));
    assert!(
        heap.get(soft_target).is_ok(),
        "soft referents survive a normal collection"
    );
    let info = WeakInfo {
        classes: &classes,
        clear_soft: true,
    };
    let (_, cleared) = heap.collect_weak([weak, soft], Some(&info));
    assert_eq!(cleared, vec![soft]);
    assert!(heap.get(soft_target).is_err());
}
