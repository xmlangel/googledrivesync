# Google Drive Sync

로컬 폴더와 Google Drive 간의 양방향 동기화를 위한 안드로이드 애플리케이션입니다.

여러 동기화 쌍 구성, 충돌 해결, WorkManager를 이용한 백그라운드 동기화 기능을 제공합니다.

[English Version (README_EN.md)](README_EN.md)

![Google Drive Sync](drivesync.png)

## 주요 기능

- **양방향 동기화**: 로컬 폴더와 Google Drive 폴더를 완벽하게 동기화합니다.
- **차분 동기화 (Changes API)**: Google Drive의 변경 사항만 똑똑하게 추적하여 대량의 파일도 눈 깜짝할 새에 동기화합니다.
- **실시간 변경 감지 (Dirty Tracking)**: 로컬 파일의 변화를 즉시 감지하여 필요한 파일만 효율적으로 업로드합니다.
- **콘텐츠 데이터 검증 (MD5)**: 파일의 시간만 보는 것이 아니라 내용(MD5)까지 확인하여 데이터 무결성을 보장합니다.
- **고급 충돌 해결**:
    - **둘 다 유지**: 충돌 시 로컬 사본을 남기고 서버 버전을 새로 받아 데이터 유실을 방지합니다.
    - **사용자 선택**: 매번 확인하거나 로컬/드라이브 우선 순위를 설정할 수 있습니다.
- **다중 동기화 쌍**: 여러 폴더를 각각 다른 구글 드라이브 경로와 연결할 수 있습니다.
- **백그라운드 서비스**: Android WorkManager를 통해 앱이 꺼져 있어도 안정적으로 자동 동기화합니다.
- **현대적인 UI**: Jetpack Compose와 Material3를 사용하여 직관적이고 아름다운 디자인을 제공합니다.

## 최신 업데이트 (v1.5.0)

- **성능 최적화**: Dirty Tracking 아키텍처 도입으로 배터리 및 데이터 사용량을 획기적으로 낮췄습니다.
- **안정성 강화**: 네트워크 오류나 코루틴 취소 시에도 데이터베이스 무결성을 유지하도록 설계를 강화했습니다.
- **정교한 충돌 해결**: '둘 다 유지(Keep Both)' 기능 추가 및 관련 테스트 검증 완료.
- **상세 릴리즈 노트**: [Docs/release_notes_v1.5.0.md](Docs/release_notes_v1.5.0.md)에서 확인 가능합니다.

## 빌드 방법

이 프로젝트를 빌드하려면 Google Cloud 프로젝트를 설정하고 필요한 자격 증명을 얻어야 합니다.

> [!CAUTION]
> **주의**: 현재 프로젝트의 패키지 이름(`uk.xmlangel.googledrivesync`)과 설정은 원본 개발 환경에 맞춰져 있습니다. 다른 환경에서 빌드하여 사용하시려면 본인의 Google Cloud 설정에 맞춰 **패키지 이름**을 변경하거나, 본인의 **SHA-1 지문**을 등록해야 합니다.

### 1. Google Cloud 설정

1. [Google Cloud Console](https://console.cloud.google.com/)에 접속합니다.
2. 새 프로젝트를 생성합니다.
3. **Google Drive API**를 활성화합니다.
4. **OAuth 동의 화면**을 구성합니다 (내부용 또는 외부용).
5. Android용 **OAuth 2.0 클라이언트 ID**를 생성합니다:
    - **패키지 이름**: `uk.xmlangel.googledrivesync`
    - **SHA-1 인증서 지문**: 빌드에 사용할 인증서의 지문을 입력해야 합니다.

### 2. SHA-1 지문 확인 방법

터미널에서 다음 명령어를 실행하여 디버그 SHA-1 지문을 확인할 수 있습니다:

```bash
./gradlew signingReport
```

`debug` 변리언트 아래의 `SHA1` 값을 확인하세요.

### 3. 로컬 설정

1. 이 저장소를 클론합니다.
2. **Android Studio (Ladybug 이상)**에서 프로젝트를 엽니다.
3. **JDK 17**이 설정되어 있는지 확인합니다.
4. 앱을 빌드하고 실행합니다.

### 4. APK 파일 생성 방법

직접 APK 파일을 생성하려면 다음 중 하나의 방법을 사용하세요:

#### 터미널(Gradle) 사용

터미널에서 다음 명령어를 실행하면 `app/build/outputs/apk/debug/` 폴더에 APK 파일이 생성됩니다.

```bash
./gradlew assembleDebug
```

#### Android Studio UI 사용

1. 상단 메뉴에서 `Build` > `Build Bundle(s) / APK(s)` > `Build APK(s)`를 선택합니다.
2. 빌드가 완료되면 오른쪽 하단에 나타나는 알림에서 `locate` 링크를 클릭하여 생성된 파일을 확인합니다.

## 개발 스택

- **언어**: Kotlin
- **UI**: Jetpack Compose (Material3)
- **데이터베이스**: Room
- **백그라운드 작업**: WorkManager
- **API**: Google Drive API (Mobile)

## 라이선스

이 프로젝트는 MIT 라이선스에 따라 라이선스가 부여됩니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.
