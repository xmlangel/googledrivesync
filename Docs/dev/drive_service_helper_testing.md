# DriveServiceHelper 테스트 명세서

이 문서는 `DriveServiceHelper.kt` 클래스의 기능과 이를 검증하는 `DriveServiceHelperTest.kt`의 테스트 내용을 상세히 설명합니다.

---

## 1. 개요

`DriveServiceHelper`는 Google Drive API를 사용하여 파일 목록 조회, 업로드, 다운로드, 폴더 생성 및 삭제 등의 기능을 수행하는 핵심 서비스 클래스입니다.

## 2. 테스트 현황 (`DriveServiceHelperTest.kt`)

현재 테스트 코드는 주로 `downloadFile` 함수의 다양한 파일 처리 시나리오를 검증하고 있습니다.

| 테스트 함수명 | 대상 메서드 | 테스트 시나리오 | 검증 포인트 |
| :--- | :--- | :--- | :--- |
| `downloadFile creates empty file for 0-byte files without calling API` | `downloadFile` | **0바이트 파일** 다운로드 시 | API를 호출하지 않고 로컬에 즉시 빈 파일 생성 여부 |
| `downloadFile uses export for Google Docs` | `downloadFile` | **Google Docs** (문서/시트 등) 다운로드 시 | `export` API 호출 및 적절한 MIME 타입 변환 여부 |
| `downloadFile uses get alt=media for normal files` | `downloadFile` | **일반 바이너리 파일** 다운로드 시 | 표준 미디어 다운로드 API 호출 여부 |

## 3. 전체 기능 및 테스트 커버리지

`DriveServiceHelper`의 전체 기능 중 테스트가 완료된 항목과 향후 추가가 필요한 항목입니다.

| 기능 분류 | 주요 메서드 | 테스트 여부 | 비고 |
| :--- | :--- | :---: | :--- |
| **인증/초기화** | [`initializeDriveService`](file:///Users/dicky/kmdata/git/googledrivesync/app/src/main/java/uk/xmlangel/googledrivesync/data/drive/DriveServiceHelper.kt#L33-L58) | ❌ | 계정 연동 및 서비스 빌드 |
| **파일 목록 조회** | [`listFiles`](file:///Users/dicky/kmdata/git/googledrivesync/app/src/main/java/uk/xmlangel/googledrivesync/data/drive/DriveServiceHelper.kt#L67-L97), [`listAllFiles`](file:///Users/dicky/kmdata/git/googledrivesync/app/src/main/java/uk/xmlangel/googledrivesync/data/drive/DriveServiceHelper.kt#L99-L113) | ❌ | 폴더 내 파일 리스트 확인 |
| **메타데이터 조회** | [`getFileMetadata`](file:///Users/dicky/kmdata/git/googledrivesync/app/src/main/java/uk/xmlangel/googledrivesync/data/drive/DriveServiceHelper.kt#L115-L123) | ❌ | 파일 상세 정보 조회 |
| **다운로드** | [`downloadFile`](file:///Users/dicky/kmdata/git/googledrivesync/app/src/main/java/uk/xmlangel/googledrivesync/data/drive/DriveServiceHelper.kt#L125-L164) | **✅ 완료** | 파일 타입별 최적화 다운로드 |
| **업로드** | [`uploadFile`](file:///Users/dicky/kmdata/git/googledrivesync/app/src/main/java/uk/xmlangel/googledrivesync/data/drive/DriveServiceHelper.kt#L182-L211), [`updateFile`](file:///Users/dicky/kmdata/git/googledrivesync/app/src/main/java/uk/xmlangel/googledrivesync/data/drive/DriveServiceHelper.kt#L213-L234) | ❌ | 신규 업로드 및 덮어쓰기 |
| **폴더 관리** | [`createFolder`](file:///Users/dicky/kmdata/git/googledrivesync/app/src/main/java/uk/xmlangel/googledrivesync/data/drive/DriveServiceHelper.kt#L236-L261) | ❌ | 새 폴더 생성 |
| **삭제** | [`delete`](file:///Users/dicky/kmdata/git/googledrivesync/app/src/main/java/uk/xmlangel/googledrivesync/data/drive/DriveServiceHelper.kt#L263-L274) | ❌ | 파일/폴더 삭제 |
| **검색** | [`searchFiles`](file:///Users/dicky/kmdata/git/googledrivesync/app/src/main/java/uk/xmlangel/googledrivesync/data/drive/DriveServiceHelper.kt#L276-L290) | ❌ | 이름 기반 파일 검색 |

---

## 4. 향후 계획

- 핵심 로직인 **업로드(`uploadFile`)** 및 **파일 목록 조회(`listFiles`)**에 대한 MockK 기반 테스트 케이스를 추가하여 동기화 안정성을 강화할 예정입니다.
