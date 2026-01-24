# Google Drive Sync

로컬 폴더와 Google Drive 간의 양방향 동기화를 위한 안드로이드 애플리케이션입니다.

여러 동기화 쌍 구성, 충돌 해결, WorkManager를 이용한 백그라운드 동기화 기능을 제공합니다.

[English Version (README_EN.md)](README_EN.md)

![Google Drive Sync](drivesync.png)

## 주요 기능

- **양방향 동기화**: 로컬 폴더와 Google Drive 폴더를 동일하게 유지합니다.
- **다중 동기화 쌍**: 여러 개의 로컬 폴더를 각각 다른 Google Drive 경로와 동기화하도록 설정할 수 있습니다.
- **충돌 해결**: 충돌 발생 시 처리 방식을 선택할 수 있습니다 (로컬 우선, 드라이브 우선, 양쪽 유지).
- **백그라운드 동기화**: Android WorkManager를 사용하여 자동 동기화를 수행합니다.
- **Material3 UI**: Jetpack Compose로 제작된 현대적이고 깔끔한 인터페이스.

## 빌드 방법

이 프로젝트를 빌드하려면 Google Cloud 프로젝트를 설정하고 필요한 자격 증명을 얻어야 합니다.

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

## 개발 스택

- **언어**: Kotlin
- **UI**: Jetpack Compose (Material3)
- **데이터베이스**: Room
- **백그라운드 작업**: WorkManager
- **API**: Google Drive API (Mobile)

## 라이선스

이 프로젝트는 MIT 라이선스에 따라 라이선스가 부여됩니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.
