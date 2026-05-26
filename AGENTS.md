# Repository Guidelines

## Project Structure & Module Organization
Recaf is a Gradle multi-project build with two main modules: `recaf-core` and `recaf-ui`. Put bytecode, mapping, IO, and service-layer code in `recaf-core/src/main/java`. Put JavaFX application code in `recaf-ui/src/main/java`; the desktop entry point is `software.coley.recaf.Main`. Module resources live in each module’s `src/main/resources`. Tests live in `src/test/java`, and shared fixtures live in `recaf-core/src/testFixtures`. Repository-level documentation and IDE code-style files are under `docs/` and `setup/`.

## Build, Test, and Development Commands
Use the Gradle wrapper from the repository root.

- `./gradlew build` - builds all modules, runs tests, and generates aggregate coverage reports.
- `./gradlew test` - runs all JUnit 5 suites across `recaf-core` and `recaf-ui`.
- `./gradlew :recaf-ui:shadowJar` - creates the distributable UI jar in `recaf-ui/build/libs/`.
- `./gradlew :recaf-ui:run` - launches the JavaFX application from source.
- `TARGET_VERSION=22 ./gradlew build` - pins the Java toolchain when your default JDK differs.

Ensure `JAVA_HOME` or `java` is available before invoking Gradle.

## Coding Style & Naming Conventions
Follow `setup/code-style-intellij.xml` or `setup/code-style-eclipsej.xml`. Java sources use tabs, UTF-8, and standard Java naming: packages in lowercase, classes in `PascalCase`, methods and fields in `camelCase`, and tests ending in `Test`. Prefer `jakarta.annotation.Nonnull` and `jakarta.annotation.Nullable` over other annotation variants. Keep changes local to the owning module; do not move UI concerns into `recaf-core`.

## Testing Guidelines
Tests use JUnit 5, Mockito, and AssertJ. Add tests beside the affected module and reuse fixtures from `recaf-core/src/testFixtures` where possible. Name test classes `*Test` and keep test resources under `src/test/resources`. UI tests in `recaf-ui` should extend `BaseFxTest` when they rely on the JavaFX test environment. New logic should include regression coverage; JaCoCo aggregation and Codecov reporting are already configured.

## Commit & Pull Request Guidelines
Recent commits use short, imperative subjects such as `Fix ...`, `Add ...`, `Improve ...`, or `Bump ...`. Keep the first line concise and scoped to one change. Pull requests should follow `PULL_REQUEST_TEMPLATE.md`: summarize what is new, what is fixed, link related issues, and include screenshots for UI-facing changes. Run `./gradlew build` before opening a PR.
