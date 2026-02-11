import argparse
import io
import json
import os
import pickle
import hashlib
import shutil
import sys
from datetime import datetime
from pathlib import Path
from typing import Dict, Set, Tuple
from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build
from googleapiclient.http import MediaIoBaseDownload, MediaFileUpload

SCOPES = ['https://www.googleapis.com/auth/drive']
TOKEN_FILE = 'token.pickle'
CREDENTIALS_FILE = 'credentials.json'
DEFAULT_SYNC_DIR = Path.home() / 'GoogleDriveSync'
FOLDER_MIME_TYPE = 'application/vnd.google-apps.folder'
GOOGLE_APPS_MIME_PREFIX = 'application/vnd.google-apps.'
BACKUP_DIR_NAME = 'conflicts_backup'
SYNC_LOG_FILENAME = 'sync.log'
MAX_LOG_SIZE_BYTES = 10 * 1024 * 1024

def _load_credentials_json():
    """credentials.json을 로드합니다. JSON 오류 시 원인을 알기 쉽게 출력합니다."""
    path = Path(CREDENTIALS_FILE)
    if not path.exists():
        print(f"오류: '{CREDENTIALS_FILE}' 파일이 없습니다.")
        print("  Google Cloud Console에서 OAuth 2.0 클라이언트 ID(데스크톱)를 만들고")
        print("  JSON을 다운로드한 뒤 이 경로에 credentials.json으로 저장하세요.")
        sys.exit(1)
    try:
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    except json.JSONDecodeError as e:
        print(f"오류: '{CREDENTIALS_FILE}' JSON 형식이 잘못되었습니다.")
        print(f"  위치: {e.lineno}번째 줄, {e.colno}번째 칸 (문자 {e.pos})")
        print(f"  내용: {e.msg}")
        if e.doc and e.pos is not None:
            start = max(0, e.pos - 20)
            end = min(len(e.doc), e.pos + 20)
            snippet = e.doc[start:end].replace('\n', ' ')
            print(f"  주변: ...{snippet}...")
        print()
        print("  흔한 원인: 닫는 괄호( }, ] ) 누락, 쉼표(,) 누락/과다, 따옴표 불일치")
        print("  한 번에 한 줄만 수정한 뒤 저장하고 다시 실행해 보세요.")
        sys.exit(1)
    except UnicodeDecodeError as e:
        print(f"오류: '{CREDENTIALS_FILE}' 인코딩 문제 (UTF-8이 아닐 수 있음).")
        print(f"  {e}")
        sys.exit(1)


def get_service():
    creds = None
    if os.path.exists(TOKEN_FILE):
        with open(TOKEN_FILE, 'rb') as token:
            creds = pickle.load(token)
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            client_config = _load_credentials_json()
            flow = InstalledAppFlow.from_client_config(client_config, scopes=SCOPES)
            creds = flow.run_local_server(port=0)
        with open(TOKEN_FILE, 'wb') as token:
            pickle.dump(creds, token)
    return build('drive', 'v3', credentials=creds)


def validate_drive_folder(service, folder_id):
    """입력한 Drive ID가 실제 동기화 가능한 폴더인지 검증합니다.

    Args:
        service: Google Drive API 서비스 객체.
        folder_id (str): 사용자가 입력한 Drive 폴더 ID.

    Raises:
        ValueError: 폴더가 아니거나 휴지통 항목인 경우.
    """
    item = service.files().get(
        fileId=folder_id,
        fields='id, name, mimeType, trashed',
        supportsAllDrives=True,
    ).execute()

    if item.get('trashed'):
        raise ValueError(
            f"선택한 항목 '{item.get('name', folder_id)}'은(는) 휴지통에 있습니다."
        )
    if item.get('mimeType') != FOLDER_MIME_TYPE:
        raise ValueError(
            "입력한 ID가 폴더가 아닙니다. "
            "Drive 폴더 URL(https://drive.google.com/drive/folders/...)의 ID를 사용하세요."
        )


def get_drive_items(service, folder_id):
    """Drive 폴더의 파일/폴더 목록을 재귀적으로 가져옵니다.

    Args:
        service: Google Drive API 서비스 객체.
        folder_id (str): 동기화할 Drive 폴더 ID.

    Returns:
        tuple[dict[str, dict], set[str]]: (파일 메타데이터 맵, 폴더 상대경로 집합).
    """
    files = {}
    folders: Set[str] = set()

    def _walk(parent_id, prefix=''):
        page_token = None
        while True:
            query = f"'{parent_id}' in parents and trashed=false"
            results = service.files().list(
                q=query,
                fields='nextPageToken, files(id, name, size, md5Checksum, modifiedTime, mimeType)',
                supportsAllDrives=True,
                includeItemsFromAllDrives=True,
                pageToken=page_token,
            ).execute()
            for item in results.get('files', []):
                mime_type = item.get('mimeType', '')
                item_name = item['name']
                rel_name = f"{prefix}/{item_name}" if prefix else item_name
                if mime_type == FOLDER_MIME_TYPE:
                    folders.add(rel_name)
                    _walk(item['id'], rel_name)
                    continue
                if mime_type.startswith(GOOGLE_APPS_MIME_PREFIX):
                    # Google Docs/Sheets/Slides는 get_media 다운로드가 불가하여 현재 스코프에서 제외.
                    print(f"Skipping non-binary Google file: {rel_name} ({mime_type})")
                    continue
                files[rel_name] = item
            page_token = results.get('nextPageToken')
            if not page_token:
                break

    _walk(folder_id)
    return files, folders


def _escape_drive_query_value(value):
    """Drive 쿼리 문자열 값을 이스케이프합니다.

    Args:
        value (str): Drive 쿼리에 넣을 원문 문자열.

    Returns:
        str: 작은따옴표가 이스케이프된 문자열.
    """
    return value.replace("\\", "\\\\").replace("'", "\\'")


def get_or_create_drive_folder(service, parent_id, folder_name, folder_cache):
    """Drive 부모 폴더 아래에 하위 폴더를 조회하거나 생성합니다.

    Args:
        service: Google Drive API 서비스 객체.
        parent_id (str): 부모 폴더 ID.
        folder_name (str): 생성/조회할 하위 폴더 이름.
        folder_cache (dict[tuple[str, str], str]): 폴더 조회 캐시.

    Returns:
        str: 하위 폴더 ID.
    """
    cache_key = (parent_id, folder_name)
    cached_id = folder_cache.get(cache_key)
    if cached_id:
        return cached_id

    escaped_name = _escape_drive_query_value(folder_name)
    query = (
        f"'{parent_id}' in parents and trashed=false and "
        f"mimeType='{FOLDER_MIME_TYPE}' and name='{escaped_name}'"
    )
    results = service.files().list(
        q=query,
        fields='files(id, name)',
        supportsAllDrives=True,
        includeItemsFromAllDrives=True,
        pageSize=1,
    ).execute()
    items = results.get('files', [])
    if items:
        folder_id = items[0]['id']
        folder_cache[cache_key] = folder_id
        return folder_id

    metadata = {
        'name': folder_name,
        'mimeType': FOLDER_MIME_TYPE,
        'parents': [parent_id],
    }
    folder_id = service.files().create(
        body=metadata,
        fields='id',
        supportsAllDrives=True,
    ).execute()['id']
    folder_cache[cache_key] = folder_id
    print(f"Created Drive folder: {folder_name}")
    return folder_id


def ensure_drive_parent_folder(service, root_folder_id, rel_path, folder_cache):
    """상대 경로의 부모 폴더 트리를 Drive에 보장하고 최종 부모 ID를 반환합니다.

    Args:
        service: Google Drive API 서비스 객체.
        root_folder_id (str): 동기화 루트 Drive 폴더 ID.
        rel_path (str): 상대 파일 경로.
        folder_cache (dict[tuple[str, str], str]): 폴더 조회 캐시.

    Returns:
        str: 해당 파일이 업로드될 Drive 부모 폴더 ID.
    """
    parent_id = root_folder_id
    rel_parent = Path(rel_path).parent
    if str(rel_parent) == '.':
        return parent_id

    for folder_name in rel_parent.parts:
        parent_id = get_or_create_drive_folder(service, parent_id, folder_name, folder_cache)
    return parent_id


def ensure_drive_folder_path(service, root_folder_id, rel_folder_path, folder_cache):
    """상대 폴더 경로가 Drive에 존재하도록 보장합니다.

    Args:
        service: Google Drive API 서비스 객체.
        root_folder_id (str): 동기화 루트 Drive 폴더 ID.
        rel_folder_path (str): 동기화 루트 기준 상대 폴더 경로.
        folder_cache (dict[tuple[str, str], str]): 폴더 조회 캐시.

    Returns:
        str: 최종 폴더 ID.
    """
    parent_id = root_folder_id
    rel_path_obj = Path(rel_folder_path)
    if str(rel_path_obj) == '.':
        return parent_id

    for folder_name in rel_path_obj.parts:
        parent_id = get_or_create_drive_folder(service, parent_id, folder_name, folder_cache)
    return parent_id


def get_local_files(sync_dir):
    """로컬 동기화 폴더의 파일 메타데이터 목록을 수집합니다.

    Args:
        sync_dir (Path): 로컬 동기화 루트 경로.

    Returns:
        dict[str, dict]: 상대 경로 기준 로컬 파일 메타데이터.
    """
    files = {}
    for path in sync_dir.rglob('*'):
        if path.is_file():
            rel_path = path.relative_to(sync_dir)
            if BACKUP_DIR_NAME in rel_path.parts:
                continue
            file_stat = path.stat()
            md5_hash = hashlib.md5(path.read_bytes()).hexdigest()
            files[str(rel_path)] = {
                'path': path,
                'md5': md5_hash,
                'modified': file_stat.st_mtime,
                'size': file_stat.st_size,
            }
    return files


def get_local_directories(sync_dir):
    """로컬 동기화 폴더 하위의 상대 디렉터리 목록을 수집합니다.

    Args:
        sync_dir (Path): 로컬 동기화 루트 경로.

    Returns:
        set[str]: 동기화 루트 기준 상대 폴더 경로 집합.
    """
    folders: Set[str] = set()
    for path in sync_dir.rglob('*'):
        if path.is_dir():
            rel_path = path.relative_to(sync_dir)
            if str(rel_path) == '.' or BACKUP_DIR_NAME in rel_path.parts:
                continue
            folders.add(str(rel_path))
    return folders


def build_tree_markdown(title, files, folders):
    """파일/폴더 목록으로 Markdown 트리를 만듭니다.

    Args:
        title (str): Markdown 문서 제목.
        files (dict[str, dict]): 상대 경로 기준 파일 메타데이터.
        folders (set[str]): 상대 경로 기준 폴더 집합.

    Returns:
        str: Markdown 형식 트리 문자열.
    """
    tree = {'dirs': {}, 'files': []}

    def _ensure_dir(root, parts):
        node = root
        for part in parts:
            node = node['dirs'].setdefault(part, {'dirs': {}, 'files': []})
        return node

    for folder in sorted(folders):
        parts = Path(folder).parts
        _ensure_dir(tree, parts)

    for rel_path, meta in sorted(files.items()):
        parts = Path(rel_path).parts
        parent = _ensure_dir(tree, parts[:-1])
        parent['files'].append((parts[-1], meta.get('size')))

    lines = [f'# {title}', '']

    def _render(node, depth):
        indent = '  ' * depth
        for dirname in sorted(node['dirs']):
            lines.append(f"{indent}- [D] {dirname}/")
            _render(node['dirs'][dirname], depth + 1)
        for filename, size in sorted(node['files'], key=lambda item: item[0]):
            size_text = size if size is not None else '?'
            lines.append(f"{indent}- [F] {filename} (size: {size_text})")

    _render(tree, 0)
    if len(lines) == 2:
        lines.append('- (empty)')
    lines.append('')
    return '\n'.join(lines)


def export_tree_markdown(output_path, title, files, folders):
    """파일/폴더 트리를 Markdown 파일로 저장합니다.

    Args:
        output_path (Path): 저장할 Markdown 파일 경로.
        title (str): Markdown 문서 제목.
        files (dict[str, dict]): 상대 경로 기준 파일 메타데이터.
        folders (set[str]): 상대 경로 기준 폴더 집합.
    """
    output_path.parent.mkdir(parents=True, exist_ok=True)
    content = build_tree_markdown(title, files, folders)
    output_path.write_text(content, encoding='utf-8')
    print(f"Tree exported: {output_path}")


def export_drive_tree_markdown(output_path, files, folders):
    """Drive 파일/폴더 트리를 Markdown 파일로 저장합니다.

    Args:
        output_path (Path): 저장할 Markdown 파일 경로.
        files (dict[str, dict]): 상대 경로 기준 Drive 파일 메타데이터.
        folders (set[str]): 상대 경로 기준 Drive 폴더 집합.
    """
    export_tree_markdown(output_path, 'Google Drive Tree', files, folders)


def export_local_tree_markdown(output_path, files, folders):
    """로컬 파일/폴더 트리를 Markdown 파일로 저장합니다.

    Args:
        output_path (Path): 저장할 Markdown 파일 경로.
        files (dict[str, dict]): 상대 경로 기준 로컬 파일 메타데이터.
        folders (set[str]): 상대 경로 기준 로컬 폴더 집합.
    """
    export_tree_markdown(output_path, 'Local Tree', files, folders)


def _normalize_size(value):
    """파일 크기 값을 정수로 정규화합니다.

    Args:
        value: 정수 또는 문자열 형태 크기.

    Returns:
        int | None: 변환된 파일 크기. 변환 불가 시 None.
    """
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def build_sync_verification_report(
    drive_files,
    drive_folders,
    local_files,
    local_folders,
):
    """Drive/Local 트리 비교 결과 리포트를 생성합니다.

    Args:
        drive_files (dict[str, dict]): Drive 파일 메타데이터.
        drive_folders (set[str]): Drive 폴더 경로 집합.
        local_files (dict[str, dict]): 로컬 파일 메타데이터.
        local_folders (set[str]): 로컬 폴더 경로 집합.

    Returns:
        tuple[bool, str]: (검증 통과 여부, Markdown 리포트).
    """
    drive_file_paths = set(drive_files)
    local_file_paths = set(local_files)
    drive_folder_paths = set(drive_folders)
    local_folder_paths = set(local_folders)

    missing_local_files = sorted(drive_file_paths - local_file_paths)
    extra_local_files = sorted(local_file_paths - drive_file_paths)
    missing_local_folders = sorted(drive_folder_paths - local_folder_paths)
    extra_local_folders = sorted(local_folder_paths - drive_folder_paths)

    size_mismatches = []
    conflict_reason_count = 0
    for path in sorted(drive_file_paths & local_file_paths):
        drive_size = _normalize_size(drive_files[path].get('size'))
        local_size = _normalize_size(local_files[path].get('size'))
        if drive_size is None or local_size is None:
            continue
        if drive_size != local_size:
            size_mismatches.append((path, drive_size, local_size))

    is_ok = not (
        missing_local_files
        or extra_local_files
        or missing_local_folders
        or extra_local_folders
        or size_mismatches
    )

    lines = ['# Sync Verification Report', '']
    lines.append(f'- Result: {"PASS" if is_ok else "FAIL"}')
    lines.append(f'- Drive files: {len(drive_file_paths)}')
    lines.append(f'- Local files: {len(local_file_paths)}')
    lines.append(f'- Drive folders: {len(drive_folder_paths)}')
    lines.append(f'- Local folders: {len(local_folder_paths)}')
    lines.append('')

    drive_file_vs_local_folder = [
        item for item in missing_local_files if item in local_folder_paths
    ]
    drive_folder_vs_local_file = [
        item for item in missing_local_folders if item in local_file_paths
    ]
    local_file_vs_drive_folder = [
        item for item in extra_local_files if item in drive_folder_paths
    ]
    local_folder_vs_drive_file = [
        item for item in extra_local_folders if item in drive_file_paths
    ]
    conflict_reason_count = (
        len(drive_file_vs_local_folder)
        + len(drive_folder_vs_local_file)
        + len(local_file_vs_drive_folder)
        + len(local_folder_vs_drive_file)
    )

    lines.append('## Failure Reasons')
    if is_ok:
        lines.append('- none')
    else:
        if missing_local_folders:
            lines.append(f'- Missing local folders: {len(missing_local_folders)}')
        if extra_local_folders:
            lines.append(f'- Extra local folders: {len(extra_local_folders)}')
        if missing_local_files:
            lines.append(f'- Missing local files: {len(missing_local_files)}')
        if extra_local_files:
            lines.append(f'- Extra local files: {len(extra_local_files)}')
        if size_mismatches:
            lines.append(f'- File size mismatches: {len(size_mismatches)}')
        if conflict_reason_count:
            lines.append(
                f'- File/Folder same-name conflicts: {conflict_reason_count} '
                '(one of possible failure reasons)'
            )
    lines.append('')

    def _format_item_with_reason(
        item,
        item_type,
        side,
    ):
        """검증 항목에 조건부 원인 설명을 추가합니다.

        Args:
            item (str): 경로 항목.
            item_type (str): 'file' 또는 'folder'.
            side (str): 'drive_only' 또는 'local_only'.

        Returns:
            str: 필요 시 원인 설명이 포함된 문자열.
        """
        reason = None
        if item_type == 'file' and side == 'drive_only' and item in local_folder_paths:
            reason = 'local path is a folder (file/folder name conflict)'
        elif item_type == 'folder' and side == 'drive_only' and item in local_file_paths:
            reason = 'local path is a file (file/folder name conflict)'
        elif item_type == 'file' and side == 'local_only' and item in drive_folder_paths:
            reason = 'drive path is a folder (file/folder name conflict)'
        elif item_type == 'folder' and side == 'local_only' and item in drive_file_paths:
            reason = 'drive path is a file (file/folder name conflict)'

        if reason is None:
            return item
        return f'{item} (reason: {reason})'

    def _append_section(title, items, item_type, side):
        lines.append(f'## {title}')
        if not items:
            lines.append('- none')
        else:
            for item in items:
                formatted_item = _format_item_with_reason(item, item_type, side)
                lines.append(f'- {formatted_item}')
        lines.append('')

    _append_section(
        'Missing Local Folders (Drive only)',
        missing_local_folders,
        'folder',
        'drive_only',
    )
    _append_section(
        'Extra Local Folders (Local only)',
        extra_local_folders,
        'folder',
        'local_only',
    )
    _append_section(
        'Missing Local Files (Drive only)',
        missing_local_files,
        'file',
        'drive_only',
    )
    _append_section(
        'Extra Local Files (Local only)',
        extra_local_files,
        'file',
        'local_only',
    )

    lines.append('## Size Mismatches')
    if not size_mismatches:
        lines.append('- none')
    else:
        for path, drive_size, local_size in size_mismatches:
            lines.append(f'- {path} (drive: {drive_size}, local: {local_size})')
    lines.append('')

    return is_ok, '\n'.join(lines)


def export_verification_report(output_path, report):
    """동기화 검증 리포트를 Markdown 파일로 저장합니다.

    Args:
        output_path (Path): 저장할 Markdown 파일 경로.
        report (str): Markdown 리포트 본문.
    """
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(report, encoding='utf-8')
    print(f"Verification report exported: {output_path}")


def export_verification_report_to_backup(backup_dir, report):
    """검증 리포트를 conflicts_backup/verify.md 로 저장합니다.

    Args:
        backup_dir (Path): 충돌 백업 루트 경로.
        report (str): Markdown 리포트 본문.
    """
    backup_report_path = backup_dir / 'verify.md'
    backup_report_path.parent.mkdir(parents=True, exist_ok=True)
    backup_report_path.write_text(report, encoding='utf-8')
    print(f"Verification report exported: {backup_report_path}")


def rotate_log_if_needed(log_path, max_size_bytes=MAX_LOG_SIZE_BYTES):
    """로그 파일이 제한 크기를 넘으면 기존 로그를 삭제합니다.

    Args:
        log_path (Path): 로그 파일 경로.
        max_size_bytes (int): 허용 최대 파일 크기(바이트).
    """
    if not log_path.exists():
        return
    if log_path.stat().st_size > max_size_bytes:
        log_path.unlink()


class TeeStream:
    """터미널 출력과 파일 출력을 동시에 수행하는 스트림입니다."""

    def __init__(self, streams):
        self._streams = streams

    def write(self, data):
        for stream in self._streams:
            stream.write(data)

    def flush(self):
        for stream in self._streams:
            stream.flush()


def _build_conflict_backup_path(backup_dir, rel_path, conflict_type):
    """충돌 백업 파일 경로를 생성합니다.

    Args:
        backup_dir (Path): 충돌 백업 루트 경로.
        rel_path (str): 동기화 루트 기준 상대 경로.
        conflict_type (str): 충돌 유형 식별자.

    Returns:
        Path: 충돌 백업 저장 경로.
    """
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    path_obj = Path(rel_path)
    backup_name = f"{path_obj.name}__{conflict_type}_{timestamp}"
    return backup_dir / conflict_type / path_obj.parent / backup_name


def move_local_file_to_conflict_backup(local_path, backup_dir, rel_path, conflict_type):
    """로컬 충돌 파일을 conflicts_backup으로 이동합니다.

    Args:
        local_path (Path): 이동할 로컬 파일 경로.
        backup_dir (Path): 충돌 백업 루트 경로.
        rel_path (str): 동기화 루트 기준 상대 경로.
        conflict_type (str): 충돌 유형 식별자.
    """
    backup_path = _build_conflict_backup_path(backup_dir, rel_path, conflict_type)
    backup_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(local_path), str(backup_path))
    print(f"Moved conflict file to backup: {backup_path}")


def download_drive_file_to_conflict_backup(service, file_id, backup_dir, rel_path, conflict_type):
    """Drive 충돌 파일을 conflicts_backup으로 다운로드합니다.

    Args:
        service: Google Drive API 서비스 객체.
        file_id (str): Drive 파일 ID.
        backup_dir (Path): 충돌 백업 루트 경로.
        rel_path (str): 동기화 루트 기준 상대 경로.
        conflict_type (str): 충돌 유형 식별자.
    """
    backup_path = _build_conflict_backup_path(backup_dir, rel_path, conflict_type)
    print(f"Downloading conflict file to backup: {backup_path}")
    download_file(service, file_id, backup_path)


def backup_conflict(local_path, backup_dir):
    backup_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    backup_path = backup_dir / f"{local_path.name}_{timestamp}"
    shutil.copy2(local_path, backup_path)
    print(f"Backed up conflict to: {backup_path}")

def download_file(service, file_id, local_path):
    request = service.files().get_media(fileId=file_id)
    local_path.parent.mkdir(parents=True, exist_ok=True)
    fh = io.FileIO(local_path, 'wb')
    downloader = MediaIoBaseDownload(fh, request)
    done = False
    while not done:
        status, done = downloader.next_chunk()
        print(f"Download {int(status.progress() * 100)}%")
    fh.close()

def upload_file(service, local_path, drive_name, parent_id):
    file_metadata = {'name': drive_name, 'parents': [parent_id]}
    media = MediaFileUpload(str(local_path), resumable=True)
    return service.files().create(body=file_metadata, media_body=media, fields='id').execute()['id']

def sync(
    sync_dir,
    drive_folder_id,
    drive_tree_md=None,
    local_tree_md=None,
    drive_tree_only=False,
    verify_sync=False,
    verify_report_md=None,
):
    """Drive 폴더와 로컬 폴더를 동기화합니다.

    Args:
        sync_dir (Path): 로컬 동기화 루트 경로.
        drive_folder_id (str): 동기화할 Drive 폴더 ID.
        drive_tree_md (Path | None): Drive 트리 Markdown 출력 경로.
        local_tree_md (Path | None): 로컬 트리 Markdown 출력 경로.
        drive_tree_only (bool): True면 트리 생성만 수행하고 종료.
        verify_sync (bool): True면 동기화 후 Drive/Local 일치 여부를 검증합니다.
        verify_report_md (Path | None): 검증 결과 Markdown 출력 경로.

    Returns:
        bool | None: 검증을 수행했으면 통과 여부, 수행하지 않았으면 None.
    """
    backup_dir = sync_dir / BACKUP_DIR_NAME
    sync_dir.mkdir(parents=True, exist_ok=True)
    backup_dir.mkdir(exist_ok=True)

    service = get_service()
    try:
        validate_drive_folder(service, drive_folder_id)
    except ValueError as error:
        print(f"오류: {error}")
        sys.exit(1)

    drive_files, drive_folders = get_drive_items(service, drive_folder_id)
    if drive_tree_md is not None:
        export_drive_tree_markdown(drive_tree_md, drive_files, drive_folders)
    if drive_tree_only:
        print("Drive tree export completed.")
        return None

    local_files = get_local_files(sync_dir)
    local_folders = get_local_directories(sync_dir)
    folder_cache: Dict[Tuple[str, str], str] = {}

    print("Scanning changes...")

    # 1. Drive에만 있는 폴더: 로컬에 생성
    for folder in sorted(drive_folders):
        local_folder_path = sync_dir / folder
        if local_folder_path.exists():
            if local_folder_path.is_file():
                print(f"Path conflict (Drive folder vs Local file): {folder}")
                move_local_file_to_conflict_backup(
                    local_folder_path,
                    backup_dir,
                    folder,
                    'drive_folder_vs_local_file',
                )
                local_folder_path.mkdir(parents=True, exist_ok=True)
            continue
        print(f"New folder from Drive: {folder}")
        local_folder_path.mkdir(parents=True, exist_ok=True)

    # 2. 로컬에만 있는 폴더: Drive에 생성
    for folder in sorted(local_folders):
        if folder not in drive_folders:
            print(f"New folder from Local: {folder}")
            ensure_drive_folder_path(service, drive_folder_id, folder, folder_cache)

    local_files = get_local_files(sync_dir)
    local_folders = get_local_directories(sync_dir)

    # 3. Drive에만 있는 파일: 다운로드
    for name, drive_file in drive_files.items():
        local_path = sync_dir / name
        if local_path.exists():
            if local_path.is_dir():
                print(f"Path conflict (Drive file vs Local folder): {name}")
                download_drive_file_to_conflict_backup(
                    service,
                    drive_file['id'],
                    backup_dir,
                    name,
                    'drive_file_vs_local_folder',
                )
            continue
        print(f"New from Drive: {name}")
        download_file(service, drive_file['id'], local_path)

    local_files = get_local_files(sync_dir)

    # 4. 로컬에만 있는 파일: 업로드
    for name, local_info in local_files.items():
        if name not in drive_files:
            print(f"New from Local: {name}")
            parent_id = ensure_drive_parent_folder(
                service, drive_folder_id, name, folder_cache
            )
            upload_file(service, local_info['path'], Path(name).name, parent_id)

    # 5. 양쪽 모두 있는 파일: 충돌 확인 및 처리
    for name in set(drive_files) & set(local_files):
        drive_file = drive_files[name]
        local_info = local_files[name]

        drive_md5 = drive_file.get('md5Checksum')
        local_md5 = local_info['md5']

        if drive_md5 != local_md5:
            # 타임스탬프 비교
            drive_time = datetime.fromisoformat(drive_file['modifiedTime'].rstrip('Z'))
            local_time = datetime.fromtimestamp(local_info['modified'])

            print(f"Conflict detected: {name} (Drive: {drive_time}, Local: {local_time})")

            # 로컬 백업 생성
            backup_conflict(local_info['path'], backup_dir)

            if drive_time > local_time:
                print(f"Drive newer -> Download: {name}")
                download_file(service, drive_file['id'], local_info['path'])
            else:
                print(f"Local newer -> Upload: {name}")
                parent_id = ensure_drive_parent_folder(
                    service, drive_folder_id, name, folder_cache
                )
                upload_file(service, local_info['path'], Path(name).name, parent_id)

    print("Sync completed!")
    final_drive_files, final_drive_folders = get_drive_items(service, drive_folder_id)
    final_local_files = get_local_files(sync_dir)
    final_local_folders = get_local_directories(sync_dir)

    if local_tree_md is not None:
        export_local_tree_markdown(local_tree_md, final_local_files, final_local_folders)

    if verify_sync or verify_report_md is not None:
        is_ok, report = build_sync_verification_report(
            final_drive_files,
            final_drive_folders,
            final_local_files,
            final_local_folders,
        )
        print(f"Verification result: {'PASS' if is_ok else 'FAIL'}")
        export_verification_report_to_backup(backup_dir, report)
        if verify_report_md is not None:
            export_verification_report(verify_report_md, report)
        return is_ok

    return None


def print_usage_guide():
    """필수 인자가 없을 때 사용 가이드를 출력합니다."""
    print("사용법: python sync.py --sync-dir <로컬폴더> --drive-folder-id <Drive폴더ID>")
    print()
    print("필수 인자:")
    print("  --sync-dir PATH         로컬 동기화 폴더 경로 (예: ~/GoogleDriveSync)")
    print("  --drive-folder-id ID    Google Drive 폴더 ID")
    print()
    print("Drive 폴더 ID 확인 방법:")
    print("  1. drive.google.com에서 동기화할 폴더를 엽니다")
    print("  2. 브라우저 주소창 URL에서 폴더 ID를 확인합니다")
    print("     예: https://drive.google.com/drive/folders/1ABC...xyz")
    print("         폴더 ID = 1ABC...xyz (슬래시 뒤 문자열)")
    print()
    print("예시:")
    print(f"  python sync.py --sync-dir {DEFAULT_SYNC_DIR} --drive-folder-id 1ABC...xyz")
    print("  python sync.py --drive-folder-id 1ABC...xyz --drive-tree-md ./drive_tree.md --drive-tree-only")
    print("  python sync.py --drive-folder-id 1ABC...xyz --local-tree-md ./local_tree.md --verify-sync --verify-report-md ./verify.md")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(
        description='Google Drive 폴더와 로컬 폴더 동기화'
    )
    parser.add_argument(
        '--sync-dir',
        type=Path,
        default=None,
        help=f'로컬 동기화 폴더 경로 (기본: {DEFAULT_SYNC_DIR})',
    )
    parser.add_argument(
        '--drive-folder-id',
        type=str,
        default=None,
        help='Google Drive 폴더 ID',
    )
    parser.add_argument(
        '--drive-tree-md',
        type=Path,
        default=None,
        help='Drive 파일/폴더 트리를 저장할 Markdown 파일 경로',
    )
    parser.add_argument(
        '--local-tree-md',
        type=Path,
        default=None,
        help='로컬 파일/폴더 트리를 저장할 Markdown 파일 경로',
    )
    parser.add_argument(
        '--drive-tree-only',
        action='store_true',
        help='동기화 없이 Drive 트리 Markdown만 생성',
    )
    parser.add_argument(
        '--verify-sync',
        action='store_true',
        help='동기화 후 Drive/Local 목록을 비교 검증',
    )
    parser.add_argument(
        '--verify-report-md',
        type=Path,
        default=None,
        help='동기화 검증 결과를 저장할 Markdown 파일 경로',
    )
    args = parser.parse_args()

    sync_dir = args.sync_dir or DEFAULT_SYNC_DIR
    drive_folder_id = args.drive_folder_id
    drive_tree_md = args.drive_tree_md
    local_tree_md = args.local_tree_md
    verify_report_md = args.verify_report_md

    if not drive_folder_id or drive_folder_id.strip() == '':
        print("오류: --drive-folder-id 를 지정해 주세요.")
        print()
        print_usage_guide()
        sys.exit(1)

    if drive_tree_md is not None:
        drive_tree_md = drive_tree_md.expanduser().resolve()
    if local_tree_md is not None:
        local_tree_md = local_tree_md.expanduser().resolve()
    if verify_report_md is not None:
        verify_report_md = verify_report_md.expanduser().resolve()

    resolved_sync_dir = sync_dir.resolve()
    backup_log_dir = resolved_sync_dir / BACKUP_DIR_NAME
    backup_log_dir.mkdir(parents=True, exist_ok=True)
    backup_log_path = backup_log_dir / SYNC_LOG_FILENAME
    script_log_path = Path(__file__).resolve().parent / SYNC_LOG_FILENAME

    rotate_log_if_needed(backup_log_path)
    rotate_log_if_needed(script_log_path)

    with (
        open(backup_log_path, 'a', encoding='utf-8') as backup_log_file,
        open(script_log_path, 'a', encoding='utf-8') as script_log_file,
    ):
        start_time = datetime.now().isoformat(timespec='seconds')
        start_line = f"\n===== Sync started: {start_time} =====\n"
        backup_log_file.write(start_line)
        script_log_file.write(start_line)
        original_stdout = sys.stdout
        original_stderr = sys.stderr
        sys.stdout = TeeStream([original_stdout, backup_log_file, script_log_file])
        sys.stderr = TeeStream([original_stderr, backup_log_file, script_log_file])
        try:
            verification_result = sync(
                resolved_sync_dir,
                drive_folder_id.strip(),
                drive_tree_md=drive_tree_md,
                local_tree_md=local_tree_md,
                drive_tree_only=args.drive_tree_only,
                verify_sync=args.verify_sync,
                verify_report_md=verify_report_md,
            )
        finally:
            end_time = datetime.now().isoformat(timespec='seconds')
            print(f"===== Sync ended: {end_time} =====")
            sys.stdout = original_stdout
            sys.stderr = original_stderr

    if args.verify_sync and verification_result is False:
        sys.exit(2)
