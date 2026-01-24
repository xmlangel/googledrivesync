# Google Drive Sync 기여 가이드

먼저, Google Drive Sync에 기여해 주셔서 감사합니다! 여러분의 참여가 이 도구를 더욱 훌륭하게 만듭니다.

[English Version (CONTRIBUTING_EN.md)](CONTRIBUTING_EN.md)

## 행동 강령

모든 상호작용에서 예의를 갖추고 전문적인 태도를 유지해 주세요.

## 기여 방법

### 버그 보고

- GitHub Issue Tracker를 이용해 주세요.
- 버그에 대한 설명과 재현 단계를 포함해 주세요.
- 해당하는 경우 Android 버전과 기기 모델을 언급해 주세요.

### 기능 제안

- GitHub Issue에 `enhancement` 태그를 달아 생성해 주세요.
- 왜 이 기능이 유용한지 설명해 주세요.

### Pull Requests (PR)

1. 저장소를 포크(Fork)합니다.
2. 새 브랜치를 생성합니다 (`git checkout -b feature/your-feature`).
3. 변경 사항을 커밋합니다.
4. 브랜치에 푸시합니다 (`git push origin feature/your-feature`).
5. Pull Request를 생성합니다.

## 개발 규칙

- Kotlin 코딩 컨벤션을 준수해 주세요.
- PR을 제출하기 전 프로젝트가 성공적으로 빌드되는지 확인해 주세요 (`./gradlew assembleDebug`).
- 가능한 경우 새로운 기능에 대한 테스트를 추가해 주세요.
