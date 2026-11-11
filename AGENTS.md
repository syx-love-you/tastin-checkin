# Repository Guidelines

## Project Structure & Module Organization

- `android/` contains the Android 7.0+ app. Java sources live in `android/app/src/main/java/com/tastin/checkin/`; layouts, drawables, and XML resources live under `android/app/src/main/res/`.
- `grab.c` and `qr_template.h` implement the Windows token extractor. `提取Token.exe` is its generated binary.
- `make_update.py` creates `update.json`, the app's in-app update manifest.
- `README.md` is user-facing product documentation. `release/使用说明.md` and `release/电脑端抓取工具/使用说明.md` provide detailed user guidance.
- `CHANGELOG.md` tracks user-visible changes. `android/README.md` and `AGENTS.md` are contributor documentation.

## Build, Test, and Development Commands

Gradle rejects non-ASCII paths, so copy `android/` to an English-only path before building:

```powershell
robocopy .\android C:\build-tastin\android /E
cd C:\build-tastin\android
$gradle = Get-ChildItem "$HOME\.gradle\wrapper\dists\gradle-9.3.1-bin\*\gradle-9.3.1\bin\gradle.bat" | Select-Object -First 1
& $gradle.FullName assembleDebug
& $gradle.FullName assembleRelease
```

Compile and validate auxiliary tools from the repository root:

```powershell
gcc -O2 -s -o 提取Token.exe grab.c
python -m py_compile make_update.py
```

## Coding Style & Naming Conventions

- Use Java 11 and four-space indentation. Keep package names lowercase; use `PascalCase` for classes, `camelCase` for methods and fields, and `UPPER_SNAKE_CASE` for constants.
- Use four spaces in C, `snake_case` for functions and variables, and keep Win32-specific code behind small helpers.
- Follow PEP 8 for Python and four-space indentation in XML. Name XML resources with lowercase `snake_case`.
- No formatter is configured. Run Android `lint` and preserve the surrounding style. Keep user-facing copy in Chinese and consistent with existing terminology.

## Testing Guidelines

There is currently no automated test suite or coverage target. Before submitting changes, run `lint` and `assembleDebug`, compile `grab.c`, and syntax-check Python files. Add Java tests as `<Class>Test.java` under `android/app/src/test/java/com/tastin/checkin/`.

Manually verify affected flows: QR token parsing, successful and failed check-ins, alarm rescheduling after reboot, retry behavior, and update manifest download plus SHA-256 validation.

## Commit & Pull Request Guidelines

Use Conventional Commits, for example `feat(android): add retry status` or `fix(grab): handle missing process`. Keep commits focused.

Pull requests should describe behavior changes, list validation commands and manual checks, link related issues, and include screenshots for UI changes.

## Security & Configuration

Never commit or expose `config.json`, `二维码.html`, `android/local.properties`, `android/app/keystore/release.jks`, or signing credentials. Bump `versionCode` for every release, keep both update URLs aligned with the published manifest, and regenerate `update.json` from the exact APK being distributed.
