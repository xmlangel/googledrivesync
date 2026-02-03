# 코드 커버리지 가이드 (Code Coverage Guide)

이 문서는 JaCoCo를 사용하여 프로젝트의 코드 커버리지를 측정하고 보고서를 확인하는 방법을 설명합니다.

## 개요

이 프로젝트는 **JaCoCo (Java Code Coverage)** 플러그인을 사용하여 단위 테스트의 코드 커버리지를 측정합니다. 테스트가 코드의 어느 부분을 실행했는지 시각적으로 확인하여 테스트의 충분성을 판단하고 취약 부분을 식별할 수 있습니다.

## 보고서 생성 방법

터미널에서 다음 Gradle 명령어를 실행하여 커버리지 데이터를 수집하고 HTML/XML 보고서를 생성할 수 있습니다.

```bash
./gradlew :app:jacocoTestReport
```

> [!NOTE]
> 이 명령은 자동으로 `testDebugUnitTest`를 먼저 실행하여 최신 테스트 결과를 반영합니다.

## 보고서 확인 방법

명령 실행이 완료되면 `app/build/reports/jacoco/` 디렉토리에 보고서가 생성됩니다.

### 1. HTML 보고서 (사람용)
브라우저에서 다음 파일을 열어 시각적으로 확인 가능합니다.
- **경로**: `app/build/reports/jacoco/jacocoTestReport/html/index.html`

### 2. XML 보고서 (도구/CI용)
CI 도구(예: SonarQube, GitHub Actions) 연동 시 사용됩니다.
- **경로**: `app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml`

## 구성 상세 (build.gradle)

- **언어**: Kotlin 소스 코드를 지원하도록 설정되었습니다.
- **제외 대상**: 커버리지 측정에 불필요한 다음 항목들은 보고서에서 제외됩니다.
    - 안드로이드 생성 클래스 (`R`, `BuildConfig`, `Manifest` 등)
    - 라이브러리 내부 클래스 (`androidx`, `com.google` 등)
    - UI 테마 관련 파일 (`ui/theme/*`)
- **수집 데이터**: `testDebugUnitTest` 실행 결과인 `.exec` 파일을 기반으로 합니다.

## 팁

- 새로운 기능을 추가한 후에는 커버리지 보고서를 생성하여 해당 기능에 대한 테스트가 누락되지 않았는지 확인하는 것이 좋습니다.
- 특정 클래스의 커버리지를 높이고 싶다면 해당 클래스를 대상으로 하는 JUnit 테스트 케이스를 추가하십시오.
