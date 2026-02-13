# Validation Rules

## Sync Exclusions
- `.git/` directory is always excluded from sync.
- Git metadata files are always excluded from sync: `.gitignore`, `.gitattributes`, `.gitmodules`, `.gitkeep`.
- This exclusion applies to both local scan and server (Google Drive) items.
- Even if these Git-related paths exist on the server, they must not be downloaded, created, tracked, or synchronized.
