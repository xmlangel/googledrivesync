# 단위 테스트 상세 명세서 (Unit Test Specifications)

이 문서는 Google Drive Sync 프로젝트에 구현된 각 단위 테스트의 목적, 대상 및 검증 내용을 상세히 설명합니다.

---

## 1. MimeTypeUtilTest

파일 확장자를 기반으로 올바른 MIME 타입을 반환하는지 검증합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `getMimeType returns correct image types` | 이미지 파일 매핑 확인 | .jpg, .JPEG, .png, .gif 확장자에 대해 `image/*` 타입을 정확히 반환하는지 확인 |
| `getMimeType returns correct document types` | 문서 파일 매핑 확인 | .pdf, .doc, .docx, .xls, .xlsx, .txt 확장자에 대해 올바른 `application/*` 또는 `text/plain` 타입을 반환하는지 확인 |
| `getMimeType returns correct media types` | 미디어 파일 매핑 확인 | .mp3, .mp4 확장자에 대해 오디오/비디오 타입을 정확히 반환하는지 확인 |
| `getMimeType returns correct obsidian and modern types` | Obsidian 및 최신 포맷 매핑 확인 | .md, .canvas, .svg, .webp 및 다양한 추가 미디어 확장자에 대해 정확한 MIME 타입을 반환하는지 확인 |
| `getMimeType returns default type for unknown extensions` | 예외 상황 처리 확인 | 알 수 없는 확장자나 확장자가 없는 파일에 대해 기본값(`application/octet-stream`)을 반환하는지 확인 |

---

## 2. SyncManagerTest (Robolectric)

핵심 동기화 관리 로직과 충돌 해결 정책을 검증합니다. 모의 객체(MockK)를 통해 외부 의존성을 제어합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `initialize` | 서비스 초기화 검증 | `DriveServiceHelper`가 성공적으로 초기화될 때 `SyncManager`의 초기화 상태가 `true`인지 확인 |
| `resolveConflict USE_LOCAL` | 로컬 우선 해결 정책 검증 | 사용자가 '로컬 유지'를 선택한 경우, 드라이브에 파일을 업로드하고 DB 상태를 `SYNCED`로 업데이트하는지 확인 |
| `resolveConflict USE_DRIVE` | 드라이브 우선 해결 정책 검증 | 사용자가 '드라이브 유지'를 선택한 경우, 파일을 다운로드하고 DB 상태를 `SYNCED`로 업데이트하는지 확인 |

---

## 3. SyncDaoTest (Robolectric + In-memory DB)

Room 데이터베이스의 쿼리 신뢰성과 데이터 정합성을 검증합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `deleteFoldersByAccount` | 데이터 정리 로직 검증 | 특정 계정을 삭제할 때 해당 계정에 속한 모든 동기화 폴더 데이터가 삭제되는지 확인 |

---

## 4. DriveServiceHelperTest

Google Drive API를 통한 파일 다운로드 및 유효성 검사 로직을 검증합니다.

| 테스트 메서드 | 테스트 목적 | 검증 내용 |
| :--- | :--- | :--- |
| `downloadFile: 0-byte file` | 0바이트 파일 처리 검증 | 파일 크기가 0인 경우 API 호출 없이 로컬에 빈 파일 생성 확인 |
| `downloadFile: Google Docs` | Google Docs 내보내기 검증 | Google Docs 타입 파일 시 `export` API를 호출하는지 확인 |
| `downloadFile: Normal file` | 일반 파일 다운로드 검증 | 일반 바이너리 파일 시 표준 다운로드 API를 호출하는지 확인 |

---

## 테스트 실행 방법

다음 명령어를 터미널에서 실행하여 위 모든 테스트를 한 번에 검증할 수 있습니다.

```bash
./gradlew test
```
