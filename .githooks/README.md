# Git Hooks

This directory contains shared Git hooks for the KidSync project.

## Setup

Run this once after cloning the repository:

```bash
git config core.hooksPath .githooks
```

This tells Git to use the hooks in this directory instead of the default `.git/hooks/`.

## Available Hooks

### pre-commit

Runs automatically before each commit. Checks:

1. **Server compilation** - If any `server/` files are staged, runs `./gradlew compileKotlin` to catch compilation errors. Skipped if `server/gradlew` is not available (e.g., Docker-only development).
2. **Android settings validation** - If any `android/` files are staged, validates `settings.gradle.kts` for known issues (Gradle 9.x compatibility, syntax).
3. **Secrets detection** - Scans all staged files for potential secrets (API keys, private keys, tokens, passwords).
4. **Large file check** - Warns about files larger than 1MB being committed.

Errors block the commit. Warnings are shown but do not block.

## Bypassing Hooks

In emergencies, you can skip the pre-commit hook:

```bash
git commit --no-verify
```

Use this sparingly - the hooks exist to prevent common mistakes.
