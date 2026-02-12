---
description: 애플리케이션 버전 관리 및 태깅 프로세스
---

# 버전 관리 워크플로우

이 프로젝트는 `Major.Minor.Build` 형식을 사용하여 버전을 관리합니다.

## 버전 규칙

- **형식:** `X.Y.Z` (예: 1.0.0, 1.1.1, 1.1.100, 1.1.1000)
- **증가 방식:**
  - `Major (X)`: 중대한 아키텍처 변경 또는 기능 개편 시 증가. **증가 시 Minor(Y)와 Build(Z) 번호는 0으로 초기화**합니다.
  - `Minor (Y)`: 새로운 기능 추가 또는 대규모 리팩토링 시 증가. **증가 시 Build(Z) 번호는 0으로 초기화**합니다.
  - `Build (Z)`: 버그 수정, 소규모 개선, 단순 배포 시 증가.
- **versionCode:** `app/build.gradle`의 `versionCode`는 배포 시마다 항상 1씩 증가해야 합니다 (버전 초기화와 상관없이 계속 증가).

## 버전 업데이트 단계

1. `app/build.gradle` 파일의 `versionName`과 `versionCode`를 확인합니다.
2. 현재 `versionName`을 기준으로 다음 버전을 결정합니다 (예: `1.0.9` -> `1.1.0` 또는 `1.0.10`).
3. `app/build.gradle`의 `versionName`을 새 버전으로 업데이트하고, `versionCode`를 1 증가시킵니다.

    ```gradle
    defaultConfig {
        ...
        versionCode 11 // 이전 값 + 1
        versionName "1.1.0" // 새 버전
    }
    ```

4. **Major 또는 Minor 버전 업데이트 시** 릴리즈 노트를 작성합니다.
   - `Docs/` 폴더에 `release_notes_vX.Y.Z.md` (한국어) 및 `release_notes_vX.Y.Z_en.md` (영어) 파일을 생성합니다.
   - 상세 규칙은 [.agent/workflows/release-notes.md](file:///Users/dicky/kmdata/git/googledrivesync/.agent/workflows/release-notes.md)를 따릅니다.

5. `DashboardScreen.kt` (또는 앱 UI에서 버전 정보를 표시하는 곳)의 버전 문자열이 동적으로 관리되는지 확인합니다.

## Git 태깅 (Tagging)

> [!IMPORTANT]
> Git 태그 생성은 사용자가 **명시적으로 요청할 때만** 수행합니다.

사용자가 태깅을 요청하면 다음 명령어를 실행합니다:

```bash
git tag -a v1.1.0 -m "Release version 1.1.0"
git push origin v1.1.0
```

## GitHub Actions 릴리즈 실행 조건

- 워크플로 파일: `.github/workflows/android_build.yml`
- `release` job은 태그 ref(`refs/tags/v*`)에서만 실행됩니다.
- `pull_request` 실행에서는 `release` job이 실행되지 않습니다.
- `release` job은 `test`와 `build`가 성공해야 실행됩니다 (`test -> build -> release`).
- `main` 브랜치는 **기술적으로 필수 조건이 아닙니다**. `v*` 태그만 push되면 실행됩니다.
- 다만 공식 배포 릴리즈는 추적성과 안정성을 위해 `main` 기준 태깅을 권장합니다.
- 예시 1 (현재와 같은 릴리즈 브랜치 태깅):
  1. `git checkout release/v1.6.5`
  2. `git tag v1.6.5`
  3. `git push origin v1.6.5`
  4. Actions에서 `event=push`, `ref=v1.6.5` 실행 확인
- 예시 2 (공식 릴리즈 권장 플로우):
  1. 릴리즈 PR을 `main`에 머지
  2. `git checkout main && git pull`
  3. `git tag v1.6.5 && git push origin v1.6.5`

## 완료 후 확인

- `./gradlew assembleDebug` 명령을 실행하여 빌드가 정상적으로 완료되는지 확인합니다.
- 변경된 `versionName`이 UI에 올바르게 표시되는지 확인합니다.
