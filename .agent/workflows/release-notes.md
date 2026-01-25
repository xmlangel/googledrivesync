---
description: 릴리즈 노트(Release Notes) 작성 가이드
---

릴리즈 노트를 작성할 때 준수해야 할 규칙입니다.

## 1. 참고 문서 (Reference)

- `Docs/` 디렉토리에 있는 기존 릴리즈 노트 파일들을 참고하여 형식을 맞춤니다.
- [release_notes_v1.0.2.md](file:///Users/dicky/kmdata/git/googledrivesync/Docs/release_notes_v1.0.2.md)와 같은 최신 버전을 우선적으로 참고합니다.

## 2. 언어 지원 (Language)

- 반드시 **한국어** 버전과 **영어** 버전이 모두 존재해야 합니다.
- 한국어 파일명: `release_notes_vX.X.X.md`
- 영어 파일명: `release_notes_vX.X.X_en.md`

## 3. 작성 규칙 (Writing Rules)

- 이모티콘을 사용하지 않습니다.
- 변경 사항(Changelog)을 명확하게 기술합니다.
- 새로운 기능, 버그 수정, 성능 개선 등으로 항목을 구분합니다.

## 4. 프로세스

1. 현재 버전 번호를 확인합니다.
2. `Docs/` 폴더 내에 해당 버전의 한국어 릴리즈 노트 파일을 생성하고 내용을 작성합니다.
3. 동일한 내용으로 영어 번역본(`_en` 접미사 포함)을 생성합니다.
4. 작성된 내용에 이모티콘이 포함되었는지 확인하고 제거합니다.
