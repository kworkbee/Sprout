# OOP · Clean Code 규칙 (Sprout)

이 프로젝트(Kotlin + Java Operator SDK)의 모든 코드 변경은 아래 규칙을 따른다. 규칙은 `./gradlew check`(ktlint, detekt, Kotest, JaCoCo 100%)로 가능한 만큼 기계적으로 강제하고, 나머지는 리뷰 기준으로 쓴다. Operator 도메인 규칙은 `k8s-operator-guide.md`를 따른다.

## 1. 단일 책임 (SRP)

- 클래스·파일은 변경 이유가 하나여야 한다. 한 클래스가 "무엇을 만들지"(매니페스트), "어떻게 적용할지"(API 호출), "언제 할지"(Reconcile 순서), "어떻게 알릴지"(status·이벤트·메트릭) 중 둘 이상을 하면 쪼갠다.
- Reconciler는 **조율(orchestration)만** 한다. 판단은 순수 함수·값 객체, 외부 쓰기는 협력 객체가 맡는다.
- 한 파일에 서로 다른 책임의 클래스를 두지 않는다. 파일명은 그 안의 대표 선언과 일치시킨다(detekt `MatchingDeclarationName`).
- 함수는 한 가지 추상화 수준만 다룬다. 60줄(detekt `LongMethod`)을 넘기 전에, 그리고 "그리고/또는"으로 설명되기 시작하면 추출한다.

## 2. 개방-폐쇄 (OCP)

- 새 하위 리소스(Kind)를 추가할 때 수정 지점은 **한 곳**이어야 한다. 적용·삭제·informer·`childKinds`가 같은 목록(`ManagedResource`)에서 파생되게 하고, 같은 사실을 여러 곳에 복붙하지 않는다.
- 분기(`if kind == ...`)가 늘어나면 `sealed` 타입이나 인터페이스 다형성으로 바꾼다.

## 3. 의존성 역전 (DIP) · 캡슐화

- 정책·도메인 로직은 인프라(fabric8 client, HTTP, Micrometer)에 의존하지 않는다. 의존 방향은 `reconcile → domain`, `reconcile → manifest → domain`, `infra`는 말단이다.
- 협력 객체는 생성자로 주입받고, 외부에 노출되는 경계(이벤트 발행 등)는 인터페이스로 둔다. 전역 상태·싱글턴·`object`에 숨은 가변 상태를 만들지 않는다.
- 가변 상태는 가능한 가장 좁은 범위에 둔다. 호출 순서에 의존하는 필드(시간적 결합) 대신 **반환값**으로 데이터를 넘긴다.
- 외부에서 필요 없는 멤버는 `private`/`internal`. 생성 후 초기화가 필요한 `lateinit`은 피하고 생성 시점에 유효한 객체를 만든다.

## 4. 명령-조회 분리 (CQS) · 부수효과

- 이름이 `check/is/get/decide`면 상태를 바꾸지 않는다. 부수효과가 있으면 동사로 드러낸다(`expire`, `record`, `apply`).
- 판단은 순수 함수(`decideTtl`, `readiness`, `isOwnedNamespace`)로 만들고 부수효과는 가장자리에서 한 번만 일으킨다.
- 함수 인자를 수정하지 않는다. 예외는 프레임워크가 요구하는 `status` 기록 한 곳(`EnvironmentStatus`)뿐이다.

## 5. 제어 흐름 · 오류 처리

- `null`을 "계속 진행/중단" 신호로 쓰지 않는다. 결과는 `sealed` 타입(`StepResult`, `SecretOutcome`, `TtlDecision`)으로 표현한다.
- 가드 절·`when` 표현식으로 중첩을 줄이고, `return`은 함수당 2개 이하(detekt `ReturnCount`).
- 예외를 삼키는 `catch`/`runCatching`은 "실패해도 본 흐름을 막지 않는다"가 명시적 요구일 때만, 범위를 좁히고 로그를 남긴다.
- 일시적 대기는 오류가 아니다(`RequeueAfter`/`rescheduleAfter`). 재시도로 해결되지 않는 입력 오류는 condition으로 알리고 재시도하지 않는다.

## 6. 이름 · 상수 · 중복 (DRY)

- 이름은 의도를 드러낸다. 표준 라이브러리와 충돌하는 이름(`apply`, `let` 등)을 쓰지 않는다.
- 문자열·숫자 리터럴(condition reason, 이벤트 타입, field manager, 기본값)은 **한 곳에서만** 정의한다. 열거할 수 있으면 `enum class`, 아니면 `const val`. detekt `MagicNumber`를 억제하지 않는다.
- 같은 지식(기본 포트, 라벨 키, 리소스 이름)을 두 곳 이상에 적지 않는다. 스키마(`@Default`)에도 쓰이는 값은 상수 하나에서 파생한다.
- 설정값은 `SproutProperties`로, 코드에 박지 않는다.

## 7. 모델링

- 원시값 대신 의미 있는 타입을 쓴다(`Duration`, `Instant`, enum). 여러 값이 늘 함께 다니면 값 객체로 묶는다(`Readiness`, `Blocked`).
- 함수가 첫 인자로 같은 객체를 반복해서 받으면(feature envy) 그 객체의 확장/메서드나 별도 클래스로 응집시킨다.
- 불변을 기본으로 한다(`val`, `List`). 가변은 직렬화 모델(`api`)처럼 프레임워크가 요구할 때만 쓰고, 그 이유가 비자명하면 한 줄 KDoc으로 남긴다.

## 7-1. Kotlin 관용구

- Java 라이브러리 객체는 빌더 체인(`XBuilder().withNewMetadata()...`) 대신 `X().apply { ... }`로 만든다. `apply` 안에서 바깥 수신자와 이름이 겹치면 값을 지역 변수로 먼저 꺼낸다.
- 첫 인자로 같은 객체를 받는 최상위 함수 대신 **확장 함수/프로퍼티**를 쓴다(`env.namespaceName`, `env.desiredService()`, `secret.isCopyable`). 부수효과 없는 파생 값은 프로퍼티, 계산·생성은 함수.
- `Class<T>` 대신 `KClass<T>`, 타입 인자가 필요한 팩토리는 `inline reified`를 쓴다. `.java`는 Java 라이브러리 경계에서만.
- 시간은 `kotlin.time`(`30.seconds`)으로 쓰고 Java API 경계에서만 `toJavaDuration()`으로 변환한다.
- `null` 처리는 `?.let`, `?:`, `orEmpty()`, `listOfNotNull`로. `!!`와 `lateinit`을 쓰지 않는다. 스레드 간 플래그는 `@Volatile var`.
- 익명 `object :` 구현이 길어지면 이름 있는 클래스로 뺀다. 콜백 람다의 인자는 `it` 대신 의미 있는 이름을 준다.

## 8. 패키지 구조

```
api        CRD 타입(직렬화 모델). 로직 없음
domain     순수 규칙: 이름·라벨, TTL, Condition, Readiness, Reason
manifest   원하는 상태 빌더(Kubernetes 객체 생성), ManagedResource
reconcile  Reconciler와 단계(Step), 동기화·정리 협력 객체
infra      메트릭·이벤트·HTTP 서버 등 외부 어댑터
config     Spring 설정, SproutProperties
tools      개발 보조 실행 파일(CRD 생성)
```

- 의존은 `reconcile → manifest → domain`, `reconcile → infra`, `manifest/reconcile → config`(값 객체 `SproutProperties`) 방향으로만. `domain`은 Kubernetes client·Spring·`config`를 import하지 않는다(fabric8 모델 타입 제외). `Cluster`/`EventPublisher` 같은 인터페이스를 통해서만 외부와 통신한다.
- 패키지가 순환하거나 `util`/`common`/`helper` 같은 잡동사니 패키지가 생기면 책임을 다시 나눈다.

## 9. 테스트

- 새·변경 코드는 JaCoCo LINE/BRANCH **100%**를 유지한다. 억제(`@Suppress`, 제외 패턴)로 맞추지 않고, 도달 불가능한 분기는 코드를 단순화해 없앤다.
- 순수 로직은 단위 테스트(Kotest), 쓰기·informer·Finalizer는 실제 kube-apiserver 통합 테스트, kubelet/GC가 필요한 것은 `test/e2e`(Kind)에서 검증한다.
- Kotest 스펙 하나는 한 단위만 다루고, 이름은 동작을 문장으로 설명한다. 테스트 간 공유 가변 상태를 만들지 않는다(클라이언트도 스펙마다 새로).

## 10. 주석 · 문서

- 코드로 의도를 드러내는 것이 우선이며 주석은 최소화한다. 다만 **코드만으로 이유를 알 수 없는 제약**(프레임워크 우회, 보안 불변식)은 한 줄 KDoc으로 남긴다.
- 설계 결정과 한계는 `docs/design.md`에 갱신한다.

## 11. 변경 전 자가 점검

1. 이 클래스의 변경 이유는 하나인가?
2. 새 Kind/단계를 추가할 때 수정할 곳이 한 곳인가?
3. 이름이 한 일을 정확히 말하는가(조회인데 쓰기를 하지 않는가)?
4. `null`·리터럴·전역 상태로 흐름이나 지식을 숨기지 않았는가?
5. `./gradlew check`가 통과하는가(커버리지 100% 포함)?
