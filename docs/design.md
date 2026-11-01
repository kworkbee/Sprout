# Sprout 설계 메모

## 소유권과 정리
- `PreviewEnvironment`는 cluster-scoped. 하위 리소스는 다른 Namespace에 있으므로 OwnerReference 대신 라벨(`sprout.dev/environment`) + Finalizer로 관리한다.
- Operator가 만든 Namespace(라벨 `managed-by=sprout`, `environment=<name>`)만 수정·삭제한다. 같은 이름의 외부 Namespace는 `NamespaceConflict`로 거부한다.
- 삭제: Finalizer가 Namespace 삭제를 요청하고 **Namespace가 사라질 때까지** CR을 유지한다. 5분 넘게 지연되면 `CleanupStuck` 이벤트(30초 간격 재확인)를 남긴다. Namespace의 외부 finalizer 때문에 영구히 막히면 `sprout.dev/skip-cleanup-wait=true` 어노테이션으로 CR을 풀어줄 수 있다.
- API 오류는 SDK 재시도(2초→최대 60초 백오프, 무제한)로 흡수한다.

## Reconcile 순서
Namespace → ResourceQuota → LimitRange → NetworkPolicy → (Secret 복사) → Service → Deployment → Ingress.
가드레일이 워크로드보다 먼저 적용되고, 모든 쓰기는 Server-Side Apply(field manager `sprout-operator`)다. 하위 리소스 변경/삭제는 label-selector informer가 감지해 CR을 재큐잉한다(드리프트 복구). 타이머는 TTL 만료와 Secret 로테이션 폴링에만 쓴다.

## 정책(플랫폼 소유)
Quota/LimitRange/NetworkPolicy 값은 `sprout.policy.*` 설정이며 CR에서 바꿀 수 없다(사용자가 가드레일을 끄지 못하게). NetworkPolicy는 같은 Namespace와 Ingress controller Namespace(`sprout.policy.ingress-controller-namespace`)에서만 인입을 허용한다.

## Secret 주입
CR에는 `secretName`만 둔다. Operator는 `sprout.secrets.source-namespace`(기본 `sprout-system`)의 Secret 중 라벨 `sprout.dev/shareable=true`인 **Opaque** 타입만 환경 Namespace로 복사하고 `envFrom`으로 주입한다. 복사본 해시를 Pod annotation에 넣어 로테이션 시 롤링된다. 한계: Operator는 클러스터 전역 Secret `get/list/watch` 권한이 필요하다(캐시는 `managed-by=sprout` 라벨로 제한).

## 가용성·관측
- Leader election: Lease `sprout-operator`(`sprout-system`), `prod` 프로파일에서 활성화, Deployment 2 replica.
- `:8081/metrics`(Prometheus: SDK reconcile/큐 메트릭 + `sprout_environments_expired_total`, `sprout_cleanup_stuck_total`, `sprout_namespace_conflicts_total`), `/healthz`, `/readyz`.
- `prod` 프로파일은 JSON 구조화 로그. 사용자 의미 있는 전이는 CR 이벤트로 남긴다.

## 한계·범위 밖
- GitHub PR 이벤트를 직접 수신하는 서버는 만들지 않았다. `examples/github/preview.yaml`처럼 CI가 CR을 생성/삭제하는 방식이다.
- 이미지는 사용자가 지정하므로 신뢰하지 않는 CR 작성자에게는 별도 admission 정책(허용 레지스트리)이 필요하다.
- Pod는 `runAsNonRoot`를 강제하지 않는다(임의 이미지 호환).
- CRD 변환/버전 승격, Helm 차트는 제공하지 않는다(Kustomize: `kubectl apply -k config/default`).

## 코드 구조 (규칙: `.claude/rules/oop-clean-code.md`)
- `api` CRD 타입, `domain` 순수 규칙(이름·TTL 판정·Readiness·Reason), `manifest` 원하는 상태 빌더와 `ManagedResource` 목록, `reconcile` Reconciler와 협력 객체(`Cluster`, `SecretSynchronizer`, `EnvironmentCleaner`, `EnvironmentStatus`, `Requeue`), `infra` 메트릭·이벤트·HTTP, `config` Spring 설정.
- 하위 리소스를 추가할 때는 `manifest/ManagedResources.kt`의 목록에 한 항목만 추가한다(적용·삭제·informer가 이 목록에서 파생된다). RBAC만 별도로 갱신한다.
