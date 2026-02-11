# 단위 테스트 상세 명세서 (Unit Test Specifications)

이 문서는 Google Drive Sync 프로젝트에 구현된 각 단위 테스트의 목적, 대상 및 검증 내용을 상세히 설명합니다.

---

## 1. UI & interaction 테스트 (Compose UI + Robolectric)

사용자 인터페이스 요소의 올바른 렌더링과 상호작용 로직을 검증합니다.

### 1.1 VersionDisplayTest
앱의 여러 화면에서 버전 정보가 올바른 형식으로 일관되게 표시되는지 확인합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `accountScreen_displaysCorrectVersion` | 계정 화면 버전 표시 | 계정 선택 화면 상단에 정확한 버전 문자열이 포함되었는지 확인 |
| `dashboardScreen_displaysCorrectVersion` | 대시보드 버전 표시 | 메인 대시보드 화면 상단에 정확한 버전 문자열이 표시되는지 확인 |
| `syncSettingsScreen_displaysCorrectVersion` | 설정 화면 버전 표시 | 동기화 설정 화면 하단에 버전 정보가 존재하는지 확인 |

### 1.2 DashboardTerminationTest
앱 종료 시의 동작과 사용자 안내 다이얼로그의 노출 여부를 검증합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `dashboardScreen_showsExitButton` | 종료 버튼 노출 확인 | 대시보드 우측 상단에 앱 종료 아이콘이 존재하는지 확인 |
| `dashboardScreen_showsExitConfirmationDialog` | 종료 확인 다이얼로그 | 종료 버튼 클릭 시 확인 다이얼로그 및 백그라운드 전환 안내 문구가 표시되는지 확인 |

---

## 2. 핵심 동기화 및 백그라운드 로직 (JUnit + MockK)

데이터 동기화의 핵심 알고리즘과 백그라운드 작업 예약 로직을 검증합니다.

### 2.1 SyncManagerTest
핵심 동기화 관리 로직과 충돌 해결 정책을 검증합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `initialize` | 서비스 초기화 검증 | DriveServiceHelper가 성공적으로 초기화될 때 SyncManager의 상태 변화 확인 |
| `resolveConflict USE_LOCAL` | 로컬 우선 해결 정책 | 사용자가 '로컬 유지' 선택 시 업로드 및 DB 업데이트 동작 확인 |
| `resolveConflict USE_DRIVE` | 드라이브 우선 해결 정책 | 사용자가 '드라이브 유지' 선택 시 다운로드 및 DB 업데이트 동작 확인 |

### 2.2 SyncMoveDetectionTest
파일 이동 감지 및 MD5 기반의 효율적인 동기화 로직을 검증합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `detects cross-folder move` | 폴더 간 이동 감지 | 드라이브 내에서 파일이 이동된 경우 로컬에서도 해당 위치로 파일을 이동시키는지 확인 |
| `avoids deletion on move` | 삭제 방지 로직 | 파일이 다른 폴더로 이동된 경우를 감지하여 단순히 로컬 파일을 삭제하지 않고 유지하는지 확인 |
| `links by MD5` | MD5 기반 자동 연결 | 파일명이나 크기가 같더라도 MD5 해시가 일치할 때만 동기화된 상태로 연결하는지 확인 |

### 2.3 SyncWorkerTest & NotificationFrequency
WorkManager 연동 및 알림 노출 빈도를 최적화하는 로직을 검증합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `doWork calls syncFolder` | 백그라운드 작업 수행 | WorkManager에 의해 모든 활성화된 폴더에 대해 동기화 요청이 전달되는지 확인 |
| `notification frequency` | 알림 스팸 방지 | 동기화 완료 시 알림이 중복으로 과도하게 호출되지 않는지(최대 2회 이내) 확인 |

### 2.4 RecursiveFileObserverTest
로컬 파일 시스템의 변경 사항을 실시간으로 감지하는 기능을 검증합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `testDetectsFileCreation` | 파일 생성 감지 | 루트 폴더 및 하위 폴더에서 새 파일이 생성될 때 이벤트를 정확히 포착하는지 확인 |

---

## 3. 유틸리티 및 데이터 모델 테스트

도우미 클래스들과 데이터 모델의 정합성을 검증합니다.

### 3.1 AppVersionUtilTest
다양한 Android SDK 버전에서의 버전 문자열 추출 로직을 검증합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `getVersionString format` | 출력 형식 확인 | `v1.2.3 (456)` 형태의 표준 형식 반환 확인 |
| `handles SDK versions` | 하위 호환성 확인 | API 27, 28, 33 등 다양한 SDK 환경에서 안정적으로 동작하는지 확인 |

### 3.2 FileUtilsTest & MimeTypeUtilTest
파일 처리 유틸리티와 확장자 기반 MIME 타입 매핑을 검증합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `sanitizeFileName` | 파일명 정제 | 특수 문자(`?`, `*`, `:`, `|` 등)를 밑줄(`_`)로 올바르게 치환하는지 확인 |
| `calculateMd5` | 해시 계산 | 파일의 MD5 해시값을 정확하게 계산하고 예외 상황(파일 없음 등)을 처리하는지 확인 |
| `getMimeType` | MIME 타입 매핑 | 이미지, 문서, 최신 포맷(Obsidian .canvas 등)에 대해 정확한 MIME 타입을 반환하는지 확인 |

### 3.3 SyncLoggerTest
로그 기록, 파일 순환(Rotation), 보관 정책을 검증합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `testLogRotation` | 로그 파일 순환 | 로그 파일 크기가 임계치(10MB)를 넘을 경우 기존 로그를 `.old`로 백업하는지 확인 |
| `testLogWithAccount` | 계정별 로그 기록 | 로그 메시지에 관련 계정 이메일 정보가 정확히 포함되는지 확인 |

---

## 4. 데이터 계층 테스트 (Room + Mock)

데이터베이스 접근 객체(DAO)와 데이터 모델의 무결성을 검증합니다.

### 4.1 SyncDaoTest
Room Database의 쿼리 결과와 데이터 조작 로직을 검증합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `deleteFoldersByAccount` | 연관 데이터 삭제 | 계정 삭제 시 해당 계정에 연결된 모든 동기화 폴더 정보가 함께 제거되는지 확인 |

### 4.2 GoogleAccountTest
Data Class의 속성과 주요 Enum(`SyncStatus`, `SyncDirection`)의 정의를 검증합니다.

---

## 5. 테스트 결과 상세 속성(Metadata) 작성 가이드

테스트 결과의 가독성을 높이고 CI/CD 연동 시 상세 정보를 제공하기 위해 `@TestMetadata` 어노테이션을 사용합니다. 이 정보는 테스트 실행 후 JUnit XML의 `<properties>` 섹션에 자동으로 기록됩니다.

### 5.1 `@TestMetadata` 사용법

테스트 메서드 상단에 어노테이션을 추가하여 테스트의 의도와 검증 단계를 명시합니다.

```kotlin
@Test
@TestMetadata(
    description = "서비스 초기화 검증",
    step = "1. DriveServiceHelper 목킹 | 2. initialize() 호출 | 3. 반환값 확인",
    expected = "DriveServiceHelper가 성공하면 true를 반환한다"
)
fun `initialize returns true when drive helper initializes successfully`() {
    // ... 테스트 코드 ...
}
```

### 5.2 속성 매핑 (XML Properties)

테스트가 완료되면 `patchJUnitXml` Gradle 태스크가 실행되어 다음과 같은 형태로 XML을 변환합니다:

| 어노테이션 필드 | XML 속성 명 (lowercase) | 설명 |
| :--- | :--- | :--- |
| `description` | `description` | 테스트 항목의 국문/영문 설명 |
| `step` | `step` | 테스트가 수행되는 단계 (파이프 `|` 구분 권장) |
| `expected` | `expected_result` | 기댓값에 대한 설명 |
| (자동 생성) | `actual_result` | 테스트 성공 시 `PASS`, 실패 시 `FAIL: [에러 메시지]` |

### 5.3 설정 방법

새 테스트 클래스에서 이 기능을 활성화하려면 `TestMetadataRule`을 `@Rule`로 추가해야 합니다.

```kotlin
class NewSyncTest {
    @get:Rule
    val metadataRule = TestMetadataRule()
    
    // ... 테스트 메서드 ...
}
```

---

## 테스트 실행 방법

터미널에서 다음 명령어를 실행하여 전체 테스트를 수행할 수 있습니다. `testDebugUnitTest` 태스크 실행 후 `patchJUnitXml`이 자동으로 실행됩니다.

```bash
./gradlew testDebugUnitTest
```
