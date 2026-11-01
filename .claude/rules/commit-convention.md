# 커밋 메시지 규칙 (Conventional Commits)

모든 커밋 메시지는 [Conventional Commits 1.0](https://www.conventionalcommits.org/)을 따른다.

```
<type>(<scope>): <subject>

<body>
```

## type

| type | 용도 |
|---|---|
| `feat` | 사용자·운영자가 체감하는 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변경 없는 구조 개선 |
| `perf` | 성능 개선 |
| `test` | 테스트 추가·수정 |
| `docs` | 문서 |
| `build` | 빌드·의존성·컨테이너 이미지·배포 매니페스트 |
| `ci` | CI 워크플로 |
| `chore` | 그 밖의 잡무(설정, 규칙 문서 등) |

## 작성 규칙

- `scope`는 선택. 패키지·영역 이름을 쓴다(`api`, `domain`, `manifest`, `reconcile`, `infra`, `config`, `e2e`, `deploy`).
- `subject`는 영어 명령형 현재형, 소문자 시작, 마침표 없음, 72자 이하.
- 본문은 *무엇을·왜*를 설명하고 72자 안팎에서 줄바꿈한다. 어떻게는 코드가 말한다.
- 하위 호환이 깨지면 `type!:` 또는 본문에 `BREAKING CHANGE:`를 쓴다.
- 한 커밋은 한 기능·한 이유. 기능과 그 테스트는 같은 커밋에 둔다.
- 공동 작성자(`Co-Authored-By`) 줄은 넣지 않는다(전역 지침).
