# Kubernetes Operator 구현 가이드 — 정석과 유의사항

> 특정 프로젝트와 무관한 교과서적 정리. Go + controller-runtime(Kubebuilder / Operator SDK) 생태계를 기준으로 서술하되, 원칙은 언어와 무관하게 적용된다.

---

## 1. Operator란 무엇인가

- **정의**: Custom Resource(CR)로 애플리케이션의 원하는 상태를 선언하고, Custom Controller가 실제 상태를 그 선언에 맞춰 지속적으로 수렴시키는 패턴. 사람 운영자(operator)의 도메인 지식을 코드로 옮긴 것이다.
- **구성 요소**
  - **CRD (CustomResourceDefinition)**: 새 API 타입의 스키마 정의
  - **Controller**: 해당 타입(및 관련 리소스)을 감시하고 Reconcile을 수행하는 루프
  - **(선택) Webhook**: 기본값 설정(Mutating), 검증(Validating), 버전 변환(Conversion)
- **언제 쓰는가**: 상태 저장 애플리케이션의 설치·업그레이드·백업·복구·장애 조치 등 "Day-2 운영"을 자동화해야 할 때. 단순한 배포 템플릿화가 목적이라면 Helm/Kustomize로 충분하며 Operator는 과하다.

### Operator 성숙도 모델 (Capability Levels)

| 레벨 | 내용 |
|---|---|
| 1. Basic Install | 자동 설치·구성 |
| 2. Seamless Upgrades | 패치·마이너 버전 업그레이드 |
| 3. Full Lifecycle | 백업, 복구, 장애 복구 |
| 4. Deep Insights | 메트릭, 알림, 로그 분석 |
| 5. Auto Pilot | 자동 스케일링, 자동 튜닝, 이상 탐지 |

---

## 2. 핵심 설계 원칙

### 2.1 Level-based (상태 기반), not Edge-based (이벤트 기반)

- Reconcile은 "무슨 이벤트가 발생했는가"가 아니라 **"지금 원하는 상태와 실제 상태가 무엇인가"**만 보고 판단해야 한다.
- 이벤트는 유실·병합·중복될 수 있다. controller-runtime의 `Request`에는 이벤트 종류(Create/Update/Delete)가 담기지 않고 `NamespacedName`만 담기는 이유가 이것이다.
- 결과적으로 Reconcile은 **언제, 몇 번 호출되어도** 같은 결과로 수렴해야 한다.

### 2.2 멱등성 (Idempotency)

- 같은 입력으로 N번 실행해도 결과가 1번 실행한 것과 같아야 한다.
- "생성" 대신 "없으면 생성, 있으면 원하는 상태로 갱신(CreateOrUpdate / Server-Side Apply)"으로 사고한다.
- 외부 시스템(클라우드 API, DB 등) 호출도 멱등하게 설계하거나, 결과를 status에 기록해 재실행 시 건너뛸 수 있게 한다.

### 2.3 선언적 API, 단일 책임

- 하나의 컨트롤러는 하나의 Kind를 "소유(For)"한다. 여러 Kind를 하나의 Reconcile에서 뒤섞어 소유하지 않는다.
- 사용자는 **무엇(what)**을 원하는지만 `spec`에 쓰고, **어떻게(how)**는 컨트롤러가 결정한다. "이 작업을 실행하라" 같은 명령형 필드는 피한다.

### 2.4 상태는 클러스터에 둔다

- 컨트롤러 프로세스 메모리에 비즈니스 상태를 두지 않는다. Pod는 언제든 재시작되며, 재시작 후에도 클러스터 상태(spec/status/관련 리소스)만으로 재구성 가능해야 한다.
- 캐시는 성능 최적화일 뿐 진실의 원천(source of truth)이 아니다.

---

## 3. API(CRD) 설계

### 3.1 Spec / Status 분리

- `spec`: 사용자가 쓰는 원하는 상태. 컨트롤러는 원칙적으로 spec을 수정하지 않는다.
- `status`: 컨트롤러가 쓰는 관측된 상태. 사용자가 쓰지 않는다.
- **status subresource를 반드시 활성화**한다. 그래야 spec 변경 시에만 `metadata.generation`이 증가하고, spec/status 업데이트 권한(RBAC)을 분리할 수 있다.

```go
// +kubebuilder:object:root=true
// +kubebuilder:subresource:status
// +kubebuilder:printcolumn:name="Ready",type=string,JSONPath=`.status.conditions[?(@.type=="Ready")].status`
// +kubebuilder:printcolumn:name="Age",type=date,JSONPath=`.metadata.creationTimestamp`
type Database struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   DatabaseSpec   `json:"spec,omitempty"`
	Status DatabaseStatus `json:"status,omitempty"`
}
```

> 위 `// +kubebuilder:` 줄은 주석이 아니라 코드 생성용 마커(marker)이므로 남겨 둔다.

### 3.2 Status 작성 규칙

- **Conditions**: `metav1.Condition` 표준 타입 사용. `type`, `status`(True/False/Unknown), `reason`(CamelCase), `message`, `lastTransitionTime`, `observedGeneration`.
- 대표 condition 타입: `Ready`(또는 `Available`), `Progressing`, `Degraded`. 긍정형 이름을 권장한다.
- **observedGeneration**: 컨트롤러가 어느 세대의 spec까지 처리했는지 기록. 클라이언트는 `status.observedGeneration == metadata.generation`일 때만 status를 신뢰한다.
- status는 **관측에서 다시 계산 가능해야** 한다. status에만 존재하고 재구성 불가능한 정보를 두지 않는다(status가 지워져도 복구 가능해야 함).
- 상태 머신을 enum 하나(`phase`)로 표현하는 방식은 확장성이 낮아 Kubernetes API 규약에서 권장하지 않는다. Conditions를 우선한다.

```go
type DatabaseStatus struct {
	ObservedGeneration int64              `json:"observedGeneration,omitempty"`
	Conditions         []metav1.Condition `json:"conditions,omitempty" patchStrategy:"merge" patchMergeKey:"type"`
	Endpoint           string             `json:"endpoint,omitempty"`
}
```

```go
meta.SetStatusCondition(&db.Status.Conditions, metav1.Condition{
	Type:               "Ready",
	Status:             metav1.ConditionTrue,
	Reason:             "Reconciled",
	Message:            "all resources are ready",
	ObservedGeneration: db.Generation,
})
```

### 3.3 검증과 기본값

- 우선순위: **OpenAPI 스키마 마커 → CEL 검증 규칙(`x-kubernetes-validations`) → Webhook**. 앞단에서 해결될수록 운영 부담(인증서, 가용성)이 줄어든다.
- 불변 필드는 CEL `self == oldSelf`로 표현할 수 있다.
- 기본값은 `+kubebuilder:default` 마커로 스키마에 넣는 것을 우선한다.

```go
type DatabaseSpec struct {
	// +kubebuilder:validation:Enum=postgres;mysql
	// +kubebuilder:validation:XValidation:rule="self == oldSelf",message="engine is immutable"
	Engine string `json:"engine"`

	// +kubebuilder:validation:Minimum=1
	// +kubebuilder:validation:Maximum=7
	// +kubebuilder:default=1
	Replicas int32 `json:"replicas,omitempty"`

	// +optional
	StorageClassName *string `json:"storageClassName,omitempty"`
}
```

### 3.4 API 규약 요점

- 선택 필드는 `omitempty` + 포인터(0값과 미설정 구분이 필요할 때).
- 필드명은 camelCase, 단위를 이름이나 타입으로 명확히(`timeoutSeconds`, `resource.Quantity`).
- 리스트는 가능하면 `listType=map` + `listMapKey`로 선언해 SSA 병합이 올바르게 동작하게 한다.
- 다른 리소스 참조는 `xxxRef`(`name`, 필요시 `namespace`) 형태로.
- Secret 값을 spec에 직접 넣지 않고 `secretRef`로 참조한다.

### 3.5 버전 관리

- `v1alpha1 → v1beta1 → v1` 순으로 성숙. 하나의 버전만 `storage: true`.
- 스키마가 호환되지 않는 버전이 공존하면 **Conversion Webhook**(Hub-and-Spoke 패턴: 하나의 Hub 버전을 두고 나머지는 Hub와 상호 변환)이 필요하다.
- 버전 제거 전 반드시 저장된 객체를 새 storage 버전으로 마이그레이션하고 `status.storedVersions`를 정리한다.
- 필드 의미 변경·삭제는 같은 버전 안에서 하지 않는다.

---

## 4. Controller 구현

### 4.1 Reconcile 루프의 정석 흐름

1. 대상 객체 조회 → `NotFound`면 정상 종료(이미 삭제됨)
2. 삭제 중(`deletionTimestamp != nil`)이면 Finalizer 정리 로직 수행 후 종료
3. Finalizer가 없으면 추가
4. 원하는 상태 계산 → 하위 리소스 생성/갱신(멱등)
5. 실제 상태 관측 → status 계산·갱신
6. 결과 반환 (에러면 백오프 재시도, 주기적 확인이 필요하면 `RequeueAfter`)

```go
func (r *DatabaseReconciler) Reconcile(ctx context.Context, req ctrl.Request) (ctrl.Result, error) {
	log := logf.FromContext(ctx)

	var db examplev1.Database
	if err := r.Get(ctx, req.NamespacedName, &db); err != nil {
		return ctrl.Result{}, client.IgnoreNotFound(err)
	}

	if !db.DeletionTimestamp.IsZero() {
		return r.finalize(ctx, &db)
	}

	if controllerutil.AddFinalizer(&db, databaseFinalizer) {
		if err := r.Update(ctx, &db); err != nil {
			return ctrl.Result{}, err
		}
		return ctrl.Result{}, nil
	}

	if err := r.reconcileStatefulSet(ctx, &db); err != nil {
		log.Error(err, "reconcile statefulset failed")
		r.setCondition(&db, metav1.ConditionFalse, "StatefulSetFailed", err.Error())
		_ = r.Status().Update(ctx, &db)
		return ctrl.Result{}, err
	}

	if err := r.reconcileService(ctx, &db); err != nil {
		return ctrl.Result{}, err
	}

	ready, err := r.isReady(ctx, &db)
	if err != nil {
		return ctrl.Result{}, err
	}

	db.Status.ObservedGeneration = db.Generation
	if ready {
		r.setCondition(&db, metav1.ConditionTrue, "Reconciled", "all resources are ready")
	} else {
		r.setCondition(&db, metav1.ConditionFalse, "Progressing", "waiting for pods")
	}
	if err := r.Status().Update(ctx, &db); err != nil {
		return ctrl.Result{}, err
	}

	if !ready {
		return ctrl.Result{RequeueAfter: 10 * time.Second}, nil
	}
	return ctrl.Result{}, nil
}
```

### 4.2 Result / error 반환 의미

| 반환 | 동작 |
|---|---|
| `Result{}, nil` | 성공. 다음 이벤트까지 대기 |
| `Result{}, err` | 지수 백오프로 재큐잉 (에러 로깅됨) |
| `Result{RequeueAfter: d}, nil` | d 후 재실행 (폴링이 필요한 외부 상태 등) |
| `Result{}, reconcile.TerminalError(err)` | 재시도해도 소용없는 에러. 재큐잉하지 않음 |

- 에러와 `RequeueAfter`를 동시에 반환하면 에러가 우선한다.
- "일시적 대기"는 에러가 아니다. 에러로 반환하면 로그·메트릭이 오염된다.

### 4.3 Watch 구성

```go
func (r *DatabaseReconciler) SetupWithManager(mgr ctrl.Manager) error {
	return ctrl.NewControllerManagedBy(mgr).
		For(&examplev1.Database{}, builder.WithPredicates(predicate.GenerationChangedPredicate{})).
		Owns(&appsv1.StatefulSet{}).
		Owns(&corev1.Service{}).
		Watches(
			&corev1.Secret{},
			handler.EnqueueRequestsFromMapFunc(r.findDatabasesForSecret),
		).
		WithOptions(controller.Options{MaxConcurrentReconciles: 2}).
		Complete(r)
}
```

- `For`: 주 리소스. `Owns`: OwnerReference로 연결된 하위 리소스 변경 시 owner를 재큐잉.
- `Watches` + `EnqueueRequestsFromMapFunc`: 소유 관계가 아닌 참조(예: `secretRef`) 변경을 감지. 역참조 조회를 위해 **필드 인덱서**를 등록한다.
- `GenerationChangedPredicate`: status·metadata만 바뀐 업데이트를 걸러 자기 자신의 status 갱신으로 인한 무한 루프를 줄인다. 단, 라벨/어노테이션 변경에 반응해야 한다면 쓰면 안 된다.
- 같은 객체(키)는 워크큐가 **동시에 두 번 처리하지 않음**을 보장하므로, `MaxConcurrentReconciles`를 올려도 객체 단위 경쟁은 없다.

```go
if err := mgr.GetFieldIndexer().IndexField(ctx, &examplev1.Database{}, ".spec.secretRef.name",
	func(o client.Object) []string {
		db := o.(*examplev1.Database)
		if db.Spec.SecretRef == nil {
			return nil
		}
		return []string{db.Spec.SecretRef.Name}
	}); err != nil {
	return err
}
```

### 4.4 OwnerReference와 Garbage Collection

- 하위 리소스에는 `controllerutil.SetControllerReference`로 controller owner를 설정한다. owner 삭제 시 GC가 자동 정리한다.
- **제약**: 네임스페이스 리소스는 같은 네임스페이스의 리소스나 클러스터 스코프 리소스만 owner로 가질 수 있다. 클러스터 스코프 리소스는 네임스페이스 리소스를 owner로 가질 수 없다. 네임스페이스를 넘는 정리는 Finalizer로 처리한다.
- 한 객체의 controller owner는 하나뿐이다.

```go
func (r *DatabaseReconciler) reconcileService(ctx context.Context, db *examplev1.Database) error {
	svc := &corev1.Service{ObjectMeta: metav1.ObjectMeta{Name: db.Name, Namespace: db.Namespace}}
	_, err := controllerutil.CreateOrUpdate(ctx, r.Client, svc, func() error {
		svc.Spec.Selector = map[string]string{"app.kubernetes.io/instance": db.Name}
		svc.Spec.Ports = []corev1.ServicePort{{Name: "db", Port: 5432}}
		return controllerutil.SetControllerReference(db, svc, r.Scheme)
	})
	return err
}
```

### 4.5 Finalizer

- **용도**: OwnerReference GC로 정리할 수 없는 것(외부 클라우드 리소스, 다른 네임스페이스 리소스, 외부 시스템 등록 해제)을 삭제 전에 정리.
- 정리 로직은 멱등해야 하며, 외부 리소스가 이미 없으면 성공으로 간주한다.
- 정리가 끝난 후에만 Finalizer를 제거한다.
- **주의**: 컨트롤러가 제거(언인스톨)된 상태에서 Finalizer가 남은 CR이 있으면 삭제가 영원히 멈춘다. 언인스톨 절차를 문서화한다.
- 외부 리소스가 없다면 Finalizer는 불필요하다. 남용하지 않는다.

```go
func (r *DatabaseReconciler) finalize(ctx context.Context, db *examplev1.Database) (ctrl.Result, error) {
	if !controllerutil.ContainsFinalizer(db, databaseFinalizer) {
		return ctrl.Result{}, nil
	}
	if err := r.External.DeleteBackupBucket(ctx, db.Name); err != nil && !isNotFound(err) {
		return ctrl.Result{}, err
	}
	controllerutil.RemoveFinalizer(db, databaseFinalizer)
	return ctrl.Result{}, r.Update(ctx, db)
}
```

### 4.6 업데이트 전략과 충돌 처리

- Kubernetes는 `resourceVersion` 기반 **낙관적 동시성 제어**를 사용한다. 오래된 객체로 Update하면 `409 Conflict`가 난다.
- 대응: 충돌 시 에러로 반환해 재큐잉하거나(가장 단순), `retry.RetryOnConflict`로 재조회 후 재시도.
- **Update vs Patch vs Apply**
  - `Update`: 전체 객체 교체. 다른 주체가 설정한 필드를 덮어쓸 위험.
  - `Patch`(Merge/Strategic Merge): 변경분만 전송.
  - **Server-Side Apply(SSA)**: 필드 소유권(`managedFields`)을 추적해 여러 주체가 같은 객체를 안전하게 공동 관리. 하위 리소스 관리에 권장되는 현대적 방식. 필드 매니저 이름을 고정하고 `ForceOwnership`을 신중히 사용한다.
- **spec과 status 갱신은 별도 호출**이다(`Update` vs `Status().Update()`). status subresource가 켜져 있으면 일반 Update는 status를 무시한다.

```go
sts := buildStatefulSet(db)
if err := r.Patch(ctx, sts, client.Apply, client.FieldOwner("database-operator"), client.ForceOwnership); err != nil {
	return err
}
```

### 4.7 무한 Reconcile 루프 방지

- 원인 1: Reconcile 중 자기 자신을 매번 Update(특히 매번 달라지는 타임스탬프를 status에 쓰기).
- 원인 2: 하위 리소스에 서버가 기본값을 채운 필드까지 비교해 항상 "다르다"고 판단 → 매번 Update → 이벤트 → 반복.
- 대응
  - 변경이 있을 때만 쓴다(`equality.Semantic.DeepEqual`로 원하는 필드만 비교, 또는 SSA 사용).
  - `meta.SetStatusCondition`은 상태가 바뀔 때만 `lastTransitionTime`을 갱신한다.
  - predicate로 불필요한 이벤트를 거른다.

---

## 5. 캐시와 클라이언트

- Manager의 기본 `client`는 **읽기는 Informer 캐시, 쓰기는 API 서버 직접**이다. 따라서 방금 쓴 내용이 바로 다음 Get에 반영되지 않을 수 있다(read-your-writes 미보장). 이 비일관성을 전제로 멱등하게 설계한다.
- 캐시는 Watch한 Kind 전체를 메모리에 올린다. Secret/ConfigMap/Pod처럼 개수가 많은 타입을 클러스터 전체로 캐싱하면 메모리가 급증한다.
  - `cache.Options`의 `ByObject`에 라벨/필드 셀렉터로 범위를 좁힌다.
  - 필요하면 메타데이터만 캐싱(`builder.OnlyMetadata`)하거나, 캐시 없이 `APIReader`로 직접 조회한다.
- 감시 범위를 특정 네임스페이스로 제한할 수 있으면 제한한다(`DefaultNamespaces`).
- 캐시에서 꺼낸 객체는 **공유 포인터**일 수 있으므로 수정 전 `DeepCopy()`한다(controller-runtime의 client.Get은 복사본을 채워 주지만, Informer/Lister를 직접 쓸 때는 주의).

---

## 6. RBAC과 보안

- **최소 권한**: `+kubebuilder:rbac` 마커로 필요한 verb만 선언. 와일드카드(`*`) 금지.
- status 갱신 권한은 `resources=databases/status`, Finalizer 갱신은 `databases/finalizers`로 별도 선언(OwnerReference의 `blockOwnerDeletion` 설정에도 필요).
- 클러스터 전체가 아닌 네임스페이스 범위로 동작할 수 있다면 `Role`을 사용.
- Secret 읽기 권한은 특히 신중히. 가능하면 특정 라벨의 Secret만 캐싱하도록 제한.
- 컨트롤러 Pod: non-root, read-only root filesystem, `allowPrivilegeEscalation: false`, capabilities drop ALL, seccomp `RuntimeDefault`.
- 사용자 입력(spec)을 그대로 셸 명령·템플릿에 넣지 않는다. CR 작성 권한만 가진 사용자가 Operator의 높은 권한을 빌려 쓰는 **권한 상승 경로**가 되지 않도록 설계한다(예: 사용자가 임의 이미지·hostPath·privileged 설정을 하위 Pod에 주입할 수 있는가?).

```go
// +kubebuilder:rbac:groups=example.com,resources=databases,verbs=get;list;watch;update;patch
// +kubebuilder:rbac:groups=example.com,resources=databases/status,verbs=get;update;patch
// +kubebuilder:rbac:groups=example.com,resources=databases/finalizers,verbs=update
// +kubebuilder:rbac:groups=apps,resources=statefulsets,verbs=get;list;watch;create;update;patch;delete
// +kubebuilder:rbac:groups=core,resources=services,verbs=get;list;watch;create;update;patch;delete
```

---

## 7. 고가용성과 운영

- **Leader Election**: 레플리카를 2개 이상 띄울 때 반드시 활성화. 리더만 Reconcile하고 나머지는 대기한다(Lease 리소스 사용).
- 리더 교체 시 짧은 공백이 있으므로, 처리 도중 중단돼도 다음 리더가 이어받을 수 있게 멱등성을 유지한다.
- **Graceful shutdown**: `ctx` 취소를 존중하고, 장시간 블로킹 호출을 피한다.
- **Health probes**: `healthz`/`readyz` 엔드포인트 제공(Manager가 기본 지원).
- **장시간 작업**: Reconcile 안에서 수 분짜리 작업(백업, 마이그레이션)을 동기 실행하지 않는다. Job을 만들고 그 상태를 관측하거나, 비동기로 시작 후 `RequeueAfter`로 폴링한다. Reconcile 하나가 오래 붙잡혀 있으면 워커가 고갈된다.
- 외부 API 호출에는 타임아웃을 건다.
- **리소스 limits**와 캐시 크기를 고려한 메모리 산정.

---

## 8. Webhook

- **Defaulting(Mutating)**, **Validation(Validating)**, **Conversion** 세 종류.
- 가능하면 CRD 스키마 기본값 + CEL 검증으로 대체해 Webhook 의존을 줄인다.
- Webhook은 API 서버 요청 경로에 끼어들기 때문에, 다운되면 해당 리소스 생성·수정이 실패한다(`failurePolicy: Fail`일 때). 가용성과 인증서 관리(cert-manager 등)를 확보한다.
- Webhook에서 외부 호출·느린 작업 금지. 빠르고 결정적이어야 한다.
- Webhook은 우회될 수 있다고 가정한다(Webhook 설치 이전에 생성된 객체, 장애 시 `Ignore` 정책 등). 컨트롤러는 Webhook을 통과하지 않은 잘못된 객체도 견뎌야 한다.
- 클러스터 내장 리소스에 대한 범용 정책이라면 `ValidatingAdmissionPolicy`(CEL 기반, Webhook 불필요)도 고려한다.

---

## 9. 관측 가능성 (Observability)

- **로그**: 구조화 로그(`logr`). Reconcile 로거에는 controller-runtime이 객체 키를 자동으로 붙인다. 정상 루프에서 Info 로그를 남발하지 않는다.
- **이벤트(Events)**: 사용자에게 의미 있는 상태 변화를 `EventRecorder`로 CR에 기록(`kubectl describe`에서 보임). 매 Reconcile마다 같은 이벤트를 남기지 않는다.
- **메트릭**: controller-runtime이 기본 제공(`controller_runtime_reconcile_total`, `..._errors_total`, `..._reconcile_time_seconds`, 워크큐 깊이 등). 도메인 메트릭은 `metrics.Registry`에 추가 등록.
- **Status Conditions**가 1차 관측 수단이다. 사용자는 로그보다 `kubectl get`의 condition을 먼저 본다.

```go
r.Recorder.Eventf(db, corev1.EventTypeWarning, "BackupFailed", "backup to %s failed: %v", bucket, err)
```

---

## 10. 테스트

| 레벨 | 도구 | 목적 |
|---|---|---|
| 단위 | Go testing, fake client | 순수 로직(원하는 상태 계산 함수) |
| 통합 | **envtest** (실제 kube-apiserver + etcd, 컨트롤러 매니저 없음) | Reconcile 동작, CRD 검증, Webhook |
| E2E | kind / k3d 등 실제 클러스터 | 실제 Pod 동작, 업그레이드, 장애 시나리오 |

- **fake client의 한계**: 실제 API 서버의 기본값 설정, 검증, `resourceVersion` 충돌, GC, status subresource 동작을 완전히 재현하지 못한다. 핵심 동작은 envtest로 검증한다.
- envtest에는 GC 컨트롤러가 없어 OwnerReference 기반 연쇄 삭제가 일어나지 않는다. "owner reference가 올바르게 설정됐는가"를 검증한다.
- 비동기 수렴은 `Eventually`(Gomega)로 단언한다.
- 원하는 상태 계산(`buildStatefulSet(db)` 등)을 클라이언트 호출과 분리하면 단위 테스트가 쉬워진다.
- 반드시 테스트할 시나리오: 재시작 후 재수렴, 하위 리소스 수동 삭제/수정 후 복구, 삭제와 Finalizer, 충돌(409), 이전 버전 CR과의 호환(업그레이드).

```go
Eventually(func(g Gomega) {
	var sts appsv1.StatefulSet
	g.Expect(k8sClient.Get(ctx, key, &sts)).To(Succeed())
	g.Expect(sts.OwnerReferences).To(HaveLen(1))
	g.Expect(*sts.Spec.Replicas).To(Equal(int32(3)))
}, 10*time.Second, 250*time.Millisecond).Should(Succeed())
```

---

## 11. 배포와 업그레이드

- CRD는 Operator보다 먼저 설치되어야 한다. Helm 사용 시 `crds/` 디렉터리는 **업그레이드 시 갱신되지 않는** 점에 유의(별도 절차 필요).
- **OLM(Operator Lifecycle Manager)**: 번들(CSV + CRD + 메타데이터)로 설치·업그레이드·의존성을 관리. OperatorHub 배포 시 사실상 표준.
- Operator 업그레이드 시 새 버전이 **이전 버전이 만든 리소스를 인식하고 이어받을 수 있어야** 한다(라벨 셀렉터·리소스 이름 규칙을 함부로 바꾸지 않는다. StatefulSet selector처럼 불변 필드는 특히 주의).
- 관리 대상 애플리케이션의 버전 업그레이드는 단계적으로: 사전 검증 → 롤링 → 상태 확인 → 실패 시 중단/롤백 지점을 status에 기록.
- CRD 삭제는 모든 CR 삭제를 의미한다. 언인스톨 스크립트에서 CRD 삭제는 명시적 옵션으로 분리한다.

---

## 12. 흔한 안티패턴 체크리스트

| 안티패턴 | 올바른 방식 |
|---|---|
| 이벤트 종류(Create/Update/Delete)에 따라 분기 | 현재 상태만 보고 수렴 |
| 메모리에 상태 보관 | 클러스터(spec/status/리소스)에서 재구성 |
| Reconcile 안에서 `time.Sleep` 대기 | `RequeueAfter` 반환 |
| 일시적 대기를 error로 반환 | `RequeueAfter` + nil |
| 매 루프마다 무조건 Update | 변경 시에만 쓰기 / SSA |
| status에 매번 현재 시각 기록 | 전이 시점만 기록 |
| spec을 컨트롤러가 수정 | 계산 결과는 status 또는 하위 리소스에 |
| `phase` 하나로 상태 표현 | Conditions + observedGeneration |
| 하나의 CR에 모든 것을 넣는 거대 CRD | 책임별로 분리된 CRD |
| 외부 리소스를 만들고 Finalizer 없음 | Finalizer로 정리 보장 |
| 외부 리소스 없는데 Finalizer 추가 | OwnerReference GC에 맡김 |
| 클러스터 전체 Secret 캐싱 | 라벨 셀렉터로 캐시 범위 제한 |
| 와일드카드 RBAC | 최소 권한 마커 |
| Webhook에만 의존한 검증 | 스키마/CEL 우선, 컨트롤러도 방어적 처리 |
| 장시간 작업을 Reconcile에서 동기 실행 | Job 생성 후 관측 |
| fake client만으로 테스트 | envtest 통합 테스트 |

---

## 13. 구현 전 자가 점검 질문

1. 이 Reconcile을 지금 100번 연속 실행해도 결과가 같은가?
2. 컨트롤러가 이 줄을 실행하다 죽으면, 재시작 후 올바르게 이어지는가?
3. 누군가 하위 리소스를 손으로 지우거나 고치면 복구되는가?
4. CR을 지우면 클러스터 안팎에 아무것도 남지 않는가?
5. 사용자가 `kubectl get`만 보고 현재 상태와 실패 원인을 알 수 있는가?
6. CR 작성 권한만 가진 사용자가 Operator 권한을 악용할 수 있는가?
7. 이전 버전 Operator가 만든 리소스를 새 버전이 그대로 이어받는가?
8. 감시하는 리소스 수가 10배가 되면 메모리·API 서버 부하는 어떻게 되는가?

---

## 출처 / 참고 자료

- Kubernetes 공식 문서 — Operator pattern: https://kubernetes.io/docs/concepts/extend-kubernetes/operator/
- Kubernetes 공식 문서 — Custom Resources: https://kubernetes.io/docs/concepts/extend-kubernetes/api-extension/custom-resources/
- Kubernetes 공식 문서 — CRD Versioning: https://kubernetes.io/docs/tasks/extend-kubernetes/custom-resources/custom-resource-definition-versioning/
- Kubernetes 공식 문서 — Finalizers: https://kubernetes.io/docs/concepts/overview/working-with-objects/finalizers/
- Kubernetes 공식 문서 — Owners and Dependents: https://kubernetes.io/docs/concepts/overview/working-with-objects/owners-dependents/
- Kubernetes 공식 문서 — Server-Side Apply: https://kubernetes.io/docs/reference/using-api/server-side-apply/
- Kubernetes 공식 문서 — Validating Admission Policy: https://kubernetes.io/docs/reference/access-authn-authz/validating-admission-policy/
- Kubernetes API Conventions (sig-architecture): https://github.com/kubernetes/community/blob/master/contributors/devel/sig-architecture/api-conventions.md
- Writing Controllers (sig-api-machinery): https://github.com/kubernetes/community/blob/master/contributors/devel/sig-api-machinery/controllers.md
- The Kubebuilder Book: https://book.kubebuilder.io/
- controller-runtime: https://pkg.go.dev/sigs.k8s.io/controller-runtime
- Operator SDK — Best Practices: https://sdk.operatorframework.io/docs/best-practices/best-practices/
- Operator SDK — Operator Capability Levels: https://sdk.operatorframework.io/docs/overview/operator-capabilities/
- Google Cloud Blog — Best practices for building Kubernetes Operators and stateful apps: https://cloud.google.com/blog/products/containers-kubernetes/best-practices-for-building-kubernetes-operators-and-stateful-apps
