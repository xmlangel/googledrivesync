# Repository Guidelines

## Project Structure & Module Organization
- Main Android app code is in `app/src/main/java/uk/xmlangel/googledrivesync`.
- Key packages:
  - `data/` (Drive API helpers, Room DB, repositories)
  - `sync/` (core sync logic, workers, file observers)
  - `ui/` (Compose screens, navigation, theme)
  - `util/` (logging, file helpers, MIME/version utilities)
- Unit tests live in `app/src/test/...`; instrumented tests live in `app/src/androidTest/...`.
- Architecture, testing, and release docs are under `Docs/` and `Docs/dev/`.

## Build, Test, and Development Commands
- `./gradlew assembleDebug` builds the debug APK.
- `./gradlew testDebugUnitTest` runs JVM unit tests (and finalizes with XML metadata patching).
- `./gradlew :app:jacocoTestReport` runs tests and generates coverage reports.
- `./gradlew signingReport` prints SHA-1 fingerprints for OAuth setup.
- Output examples:
  - APK: `app/build/outputs/apk/debug/`
  - Coverage HTML: `app/build/reports/jacoco/jacocoTestReport/html/index.html`

## Coding Style & Naming Conventions
- Follow Kotlin conventions: 4-space indentation, clear null-safety, small focused functions.
- Keep package names lowercase; classes/screens use `PascalCase` (for example, `SyncManager`, `DashboardScreen`).
- Test names should describe behavior; backtick style is acceptable for readability.
- Keep UI logic in `ui/`, sync/business logic in `sync/`, and persistence/API code in `data/`.

## Testing Guidelines
- Frameworks in use: JUnit4, MockK, Robolectric, Turbine, Compose test APIs.
- Add or update tests for behavior changes in sync logic, DB queries, and UI-visible flows.
- Prefer deterministic tests with clear Arrange-Act-Assert structure.
- Validate coverage changes with `:app:jacocoTestReport` for critical paths.

## Commit & Pull Request Guidelines
- Use Conventional Commit prefixes seen in history: `feat:`, `fix:`, `docs:`, `chore:`.
- Keep commit messages imperative and scoped (for example, `fix: prevent EISDIR in syncDirtyItems`).
- PRs should include:
  - concise problem/solution summary
  - linked issue(s) when applicable
  - test evidence (`./gradlew testDebugUnitTest` output)
  - UI screenshots/video for Compose screen changes

## Security & Configuration Tips
- Do not commit secrets or local credential files.
- OAuth/Drive setup depends on package name `uk.xmlangel.googledrivesync` and SHA-1 registration.
- Review `local.properties` and signing settings before sharing builds.

## Release Workflow Conditions
- Workflow file: `.github/workflows/android_build.yml`
- `release` job runs only when `github.ref` starts with `refs/tags/v` (tag push like `v1.6.5`).
- `pull_request` runs do not execute `release` job.
- `release` job depends on successful completion of `test` and `build` jobs (`needs: build`).
- `main` is not a technical requirement for triggering `release`; any branch/commit works if a `v*` tag is pushed.
- For official production releases, tagging on `main` is still recommended.
- Example (same as current flow, from release branch):
  1. `git checkout release/v1.6.5`
  2. `git tag v1.6.5`
  3. `git push origin v1.6.5`
  4. Confirm Actions event is `push` on `v1.6.5` and `release` job starts after `test`/`build`.
- Example (official release on main):
  1. Merge PR to `main`
  2. `git checkout main && git pull`
  3. `git tag v1.6.5 && git push origin v1.6.5`
