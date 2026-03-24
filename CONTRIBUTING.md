# Contributing to Iceberg Lens

Thank you for your interest in contributing to Iceberg Lens.

## Prerequisites

- JDK 17 or later
- Git

## Getting started

```bash
# Clone the repository
git clone https://github.com/mmdemirbas/ice-lens.git
cd ice-lens

# Build
./gradlew build

# Run
./gradlew run

# Run tests
./gradlew test
```

## Project structure

See [CLAUDE.md](CLAUDE.md) for a detailed architecture overview, key conventions, and known quirks. Key architecture docs:

- [CLAUDE.md](CLAUDE.md) -- file-level architecture, conventions, extension guide
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) -- layer diagram, data flow, threading model
- [CHANGELOG.md](CHANGELOG.md) -- version history

## Making changes

1. Fork the repository and create a feature branch from `main`.
2. Make your changes. Follow the existing code style (Kotlin official conventions).
3. Add or update tests for your changes.
4. Run `./gradlew test` and make sure all tests pass.
5. Run `./gradlew build` to verify the full build succeeds.
6. Open a pull request against `main`.

## Code style

- Follow Kotlin official coding conventions.
- Match the patterns you see in existing code.
- Keep changes focused. One PR per logical change.
- Add comments only where the logic is not self-evident.

## Reporting bugs

Use [GitHub Issues](https://github.com/mmdemirbas/ice-lens/issues) with the Bug Report template. Include:

- Steps to reproduce
- Expected vs actual behavior
- Your OS, Java version, and app version (available in About > Copy diagnostic info)

## Suggesting features

Use [GitHub Issues](https://github.com/mmdemirbas/ice-lens/issues) with the Feature Request template.
