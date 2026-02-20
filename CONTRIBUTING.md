# Contributing to KidSync

## Getting Started

1. Fork the repository
2. Create a feature branch from `main`
3. Make your changes
4. Run tests to verify
5. Submit a pull request

## Development Setup

### Server

The server requires JDK 21. Use Docker for a consistent environment:

```bash
# Run tests
docker run --rm -v "$(pwd)/server:/app" -w /app gradle:8.12-jdk21 gradle test --no-daemon

# Build fat JAR
docker run --rm -v "$(pwd)/server:/app" -w /app gradle:8.12-jdk21 gradle buildFatJar --no-daemon
```

### Android

- Android Studio with Kotlin plugin
- Min SDK 26, Target SDK 35
- JDK 17 for compilation

## Code Style

- **Language**: Kotlin for both server and Android
- **Commits**: Use [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `chore:`, `docs:`, etc.)
- **Formatting**: Follow Kotlin coding conventions
- **Testing**: All new features must include tests. All existing tests must pass.

## Pull Request Guidelines

- Keep PRs focused on a single concern
- Include a clear description of what changed and why
- Ensure all tests pass before requesting review
- Update documentation if behavior changes

## Testing Requirements

- Server: All 44+ tests must pass (`gradle test`)
- Android: Unit tests must pass
- New features should include appropriate test coverage

## Security

If you discover a security vulnerability, please report it privately rather than opening a public issue. See the security contact in the README.

## License

By contributing, you agree that your contributions will be licensed under the AGPLv3 license.
