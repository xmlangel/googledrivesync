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
