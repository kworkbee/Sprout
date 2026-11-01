# Sprout 🌱

> **Kubernetes-native Ephemeral Environment Operator**  
> *Grow isolated preview environments on demand. Reclaim them when they're no longer needed.*

Sprout는 Kubernetes에서 PR 또는 개발 요청별 **격리된 임시(Preview) 환경**을 선언적으로 생성·관리·정리하는 Operator다. Kotlin, Spring Boot, Java Operator SDK(fabric8)로 구현했다.

- **개발자 셀프서비스:** `PreviewEnvironment` 리소스 하나로 Namespace, 워크로드, 접속 URL까지 구성된다.
- **환경 격리:** 환경마다 전용 Namespace, ResourceQuota, LimitRange, NetworkPolicy가 적용된다.
- **수명주기 자동화:** TTL이 지나거나 CR을 삭제하면 관련 자원이 모두 정리된다.
- **복원력:** Controller가 재시작되거나 하위 리소스가 지워져도 선언된 상태로 다시 수렴한다.

## 동작 흐름

```text
GitHub Actions / CI / kubectl
             |
             v
  PreviewEnvironment CR (cluster-scoped)
             |
             v
       Sprout Controller  (replica 2, leader election)
             |
      Kubernetes API (Server-Side Apply)
             |
    +--------+---------+
    v                  v
Namespace sprout-pr-101    Namespace sprout-pr-102
  ResourceQuota / LimitRange / NetworkPolicy
  Deployment · Service · Ingress · (Secret)
    |                  |
    +-------> http://pr-101.localhost:8080 ...

    TTL 만료 / 삭제 -> Finalizer가 Namespace 삭제 완료까지 보장
```

1. 개발자 또는 CI가 `PreviewEnvironment`를 생성한다.
2. Controller가 `sprout-<name>` Namespace와 가드레일(Quota, LimitRange, NetworkPolicy)을 먼저 만들고, 이어서 Secret 복사, Service, Deployment, Ingress를 적용한다.
3. 준비 상태와 접속 URL을 `status`에 기록한다.
4. TTL 만료 또는 삭제 요청 시 Namespace가 사라질 때까지 Finalizer가 CR을 유지한다.

## PreviewEnvironment API

`sprout.dev/v1alpha1`, **cluster-scoped**, 짧은 이름 `pe`. namespaced CR은 다른 Namespace를 소유할 수 없으므로 cluster-scoped로 두고, 하위 리소스는 라벨과 Finalizer로 관리한다.

```yaml
apiVersion: sprout.dev/v1alpha1
kind: PreviewEnvironment
metadata:
  name: pr-184            # DNS-1123 라벨, 56자 이하 (Namespace는 sprout-pr-184)
spec:
  image: ghcr.io/example/payment-api:pr-184   # 필수
  port: 8080              # 기본 8080, 1-65535
  ttl: 4h                 # 선택. 30s / 30m / 4h / 2d. 생성 시각 기준. 없으면 만료 없음
  resources:              # 선택. 컨테이너 requests/limits
    requests: {cpu: 100m, memory: 128Mi}
    limits: {cpu: 500m, memory: 512Mi}
  env:                    # 선택. 일반 환경변수
    - {name: GREETING, value: hello}
  secretName: payment-db  # 선택. 아래 "Secret 주입" 참고
  ingress: {}             # 선택. 지정하면 Ingress와 Preview URL 생성 (enabled: false로 끌 수 있음)
```

```yaml
status:
  observedGeneration: 1
  namespace: sprout-pr-184
  url: http://pr-184.localhost:8080
  expiresAt: "2026-11-01T13:06:17Z"
  readyReplicas: 1
  conditions:
    - type: Ready
      status: "True"
      reason: WorkloadsAvailable
```

`Ready` condition의 reason: `WorkloadsAvailable`, `Progressing`, `InvalidTTL`, `NamespaceConflict`, `NamespaceTerminating`, `SecretUnavailable`. `kubectl describe`에는 `TTLExpired`, `CleanupStuck` 등 전이 이벤트가 남는다.

## 격리 정책

Quota, LimitRange, NetworkPolicy 값은 플랫폼이 정하는 설정(`sprout.policy.*`)이며 CR에서 바꿀 수 없다.

| 리소스 | 기본값 |
|---|---|
| ResourceQuota | pods 10, requests cpu 2 / memory 2Gi, limits cpu 4 / memory 4Gi |
| LimitRange | 기본 request 100m / 128Mi, 기본 limit 500m / 512Mi, 컨테이너 최대 2 / 2Gi |
| NetworkPolicy | 같은 Namespace와 `ingress-nginx` Namespace에서 오는 인입만 허용 |

Pod는 `allowPrivilegeEscalation=false`, seccomp `RuntimeDefault`, ServiceAccount 토큰 미마운트로 실행된다.

## Secret 주입

CR에는 `secretName`만 둔다. Operator는 `sprout.secrets.source-namespace`(기본 `sprout-system`)에서 라벨 `sprout.dev/shareable=true`가 붙은 **Opaque** Secret만 환경 Namespace로 복사하고 `envFrom`으로 주입한다. 복사본의 해시가 Pod annotation에 들어가므로 원본 Secret이 바뀌면 롤링 재시작된다(5분 주기로 확인).

## 시작하기

필요한 것: JDK 25, Docker, Kind, kubectl.

```bash
./gradlew check          # ktlint + detekt + Kotest + JaCoCo(라인·브랜치 100%)
./gradlew generateCrd    # config/crd 재생성 (CI에서 최신 여부 검증)
```

**로컬에서 실행** (현재 kubeconfig 컨텍스트 대상):

```bash
kubectl apply -f config/crd/
./gradlew bootRun
kubectl apply -f examples/pr-184.yaml
kubectl get pe
```

**Kind 클러스터에 배포** (Control Plane 1 + Worker 2, ingress-nginx 포함):

```bash
kind create cluster --name sprout --config kind-config.yaml
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.12.1/deploy/static/provider/kind/deploy.yaml
./gradlew bootJar
docker build -t sprout-operator:dev . && kind load docker-image sprout-operator:dev --name sprout
kubectl apply -k config/default
kubectl apply -f examples/pr-184.yaml
```

`test/e2e/run.sh`가 위 과정과 검증 시나리오를 한 번에 수행한다. Kind 포트 매핑 덕분에 `http://<name>.localhost:8080`으로 접속할 수 있다.

## 설정

`application.yaml` 또는 환경변수(`SPROUT_INGRESS_DOMAIN`처럼)로 지정한다.

| 설정 | 기본값 | 설명 |
|---|---|---|
| `sprout.ingress.domain` / `port` / `class-name` | `localhost` / `8080` / 없음 | Preview URL 구성과 IngressClass |
| `sprout.secrets.source-namespace` | `sprout-system` | 복사 가능한 Secret이 있는 Namespace |
| `sprout.policy.*` | 위 표 | Quota, LimitRange, ingress controller Namespace |
| `sprout.leader-election.enabled` / `namespace` | `false` / `sprout-system` | `prod` 프로파일에서는 활성화 |
| `sprout.metrics.port` | `8081` | `/metrics`, `/healthz`, `/readyz` |

## 운영

- **고가용성:** 2 replica, Lease `sprout-operator`로 리더 선출. 리더가 죽으면 다른 Pod가 이어받는다.
- **관측:** `:8081/metrics`(SDK reconcile·큐 메트릭, `sprout_environments_expired_total`, `sprout_cleanup_stuck_total`, `sprout_namespace_conflicts_total`), `prod` 프로파일은 JSON 로그.
- **삭제가 멈췄을 때:** Namespace가 5분 넘게 Terminating이면 `CleanupStuck` 이벤트가 남는다. Namespace의 외부 finalizer를 해결하거나, CR에 `sprout.dev/skip-cleanup-wait=true` 어노테이션을 달면 기다리지 않고 CR을 풀어준다.
- **안전 장치:** 같은 이름의 외부 Namespace가 있으면 `NamespaceConflict`로 멈추고 건드리지 않는다. Operator가 만든 Namespace(라벨 확인)만 삭제한다.

## 검증

`test/e2e/run.sh`(Kind)가 확인하는 시나리오:

| 시나리오 | 기대 결과 |
|---|---|
| CR 생성 | Namespace, 가드레일, 워크로드 생성 후 Ready, URL 접속 |
| 환경변수·Secret 주입 | 컨테이너에서 값 확인, shareable이 아닌 Secret은 거부 |
| 환경 간 격리 | 같은 Namespace 통신 허용, 다른 환경 통신 차단, Quota 초과 Pod 거부 |
| 관리 리소스 수동 삭제 | Deployment, Ingress 재생성 |
| 스펙 변경 | 같은 Deployment가 제자리에서 갱신 |
| 동시 10개 환경 생성 | 이름 충돌이나 자원 교차 없이 모두 Ready |
| 리더 Pod 강제 종료, Controller 중단 중 변경 | 새 리더가 복구, 재시작 후 수렴 |
| 삭제 중 API 권한 오류, Namespace 고착 | 오류 해소 후 정리 완료, skip 어노테이션으로 해제 |
| TTL 만료 | CR과 Namespace 정리 |
| 메트릭 | 도메인 카운터 노출 |

`./gradlew check`는 실제 kube-apiserver(Docker)를 띄워 Reconcile, Finalizer, 드리프트 복구, 네임스페이스 충돌 등을 검증한다. kubelet과 가비지 컬렉션이 필요한 항목은 위 E2E에서 확인한다.

## 프로젝트 구조

```text
src/main/kotlin/me/tommy/g1/sprout/
├── api/        CRD 타입 (PreviewEnvironment)
├── domain/     이름·소유권, TTL 판정, Condition, Readiness, Reason
├── manifest/   원하는 상태 빌더, ManagedResource 목록
├── reconcile/  Reconciler, Cluster, SecretSynchronizer, EnvironmentCleaner
├── infra/      메트릭, 이벤트, 프로브 서버
├── config/     Spring 설정, SproutProperties
└── tools/      CRD 생성기
config/         crd, rbac, manager, default (Kustomize)
examples/       pr-184.yaml, github/preview.yaml (PR 단위 환경 생성·삭제 Actions 예제)
test/e2e/       Kind E2E 및 장애 주입 스크립트
docs/design.md  설계 메모
```

코딩 규칙은 `.claude/rules/`(OOP·Clean Code, Kubernetes Operator, 커밋 메시지)를 따른다.

## 제한 사항

- GitHub PR 이벤트를 직접 수신하는 서버는 없다. CI가 CR을 만들고 지우는 방식이다(`examples/github/preview.yaml`).
- 이미지는 CR 작성자가 지정한다. 신뢰하지 않는 작성자에게는 별도 admission 정책(허용 레지스트리)이 필요하다.
- Pod에 `runAsNonRoot`를 강제하지 않는다(임의 이미지 호환).
- Operator는 Secret 복사를 위해 클러스터 전역 Secret `get/list/watch` 권한이 필요하다(캐시는 `managed-by=sprout` 라벨로 제한).
- API Group `sprout.dev`의 도메인 소유권은 확인하지 않았다. 공개 배포 시 실제로 제어하는 그룹으로 바꾼다.
- 클라우드 관리형 인프라 프로비저닝, 멀티클러스터, Helm 차트, CRD 버전 변환은 범위 밖이다.

## 참고 문서

- [Java Operator SDK](https://javaoperatorsdk.io/)
- [Kind](https://kind.sigs.k8s.io/)
- [Kubernetes Custom Resources](https://kubernetes.io/docs/concepts/extend-kubernetes/api-extension/custom-resources/)
- [Kubernetes Finalizers](https://kubernetes.io/docs/concepts/overview/working-with-objects/finalizers/)
