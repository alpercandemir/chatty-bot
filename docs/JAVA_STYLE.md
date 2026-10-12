# Java conventions

The [Java coding agent guidelines](../backend/AGENTS.md) contain the shared rules for writing, changing, and reviewing Java in this repository. They apply throughout `backend/`; the repository-specific section preserves the established Java version, formatter, and transaction boundaries.

Use four spaces for each block and the continuation indentation produced by Spotless's pinned google-java-format AOSP style. This project adopts Google Java Style's structural rules while honoring the requested four-space indentation; Google's default style uses two spaces.

- Use explicit imports, including static imports. Keep one variable declaration and one statement per line.
- Use braces for every conditional and loop body. Let the formatter place braces and wrap expressions.
- Name variables and parameters after their domain role: `conversation`, `run`, `action`, `workspaceId`, and `context`. Short loop indexes are appropriate when their purpose is clear.
- Keep methods focused on a coherent step. Extract processing, validation, persistence, and result publication when a long method mixes these responsibilities. Avoid helpers that merely hide an obvious single operation.
- Prefer guard clauses when they clarify the normal flow. Keep the sequence of validation, state transitions, and side effects explicit.
- Name limits and time budgets. Include units in names or use `Duration` and `TimeUnit` to make conversions clear.
- Preserve transaction boundaries, ownership checks, idempotency, event order, retry limits, and externally visible JSON when refactoring. Run the acceptance suite after structural changes.
- Keep database migrations unchanged once applied; formatting them changes Flyway checksums.

Run `mvn -f backend/pom.xml spotless:apply` to format Java. `mvn -f backend/pom.xml verify` checks formatting and structural rules and runs the tests. The repository's `npm run format` and `npm run format:check` commands also include Java formatting.

## Automated checks and review signals

Spotless enforces formatting. Checkstyle fails the build for wildcard imports, missing braces, multiple variable declarations in a statement, and multiple statements on one line.

Checkstyle also emits warnings at more than 50 non-empty/comment-free method lines, boolean-expression complexity above three operators, and three nested `if`, `for`, or `try` constructs of the same kind. These are prompts to review intent and cohesion, not hard design limits. Checkstyle's nesting checks do not measure mixed control-flow nesting, so review combinations of loops, conditionals, and switches manually. Methods around 30 lines should still be reviewed even if they do not trigger the automatic warning.

The current refactoring separates tool retry orchestration, validated invocation, provider checkpoints, and error resolution. Guard clauses simplify session lookup and confirmed-action execution. The acceptance suite checks transient-read retries, ownership, idempotency, confirmation, provider diagnostics, and the single argument-repair allowance.

Some existing methods still produce length warnings. `Harness.message` groups acceptance, deduplication, and state transitions under one row lock; `Harness.executeRun` keeps semaphore release beside run lifecycle handling. Existing scenario dispatch/rehearsal methods, provider request assembly, and complete journey tests also remain review signals. They are not suppressed or automatically split solely to satisfy a line-count target. Review them when their behavior changes, and extract a step only when its name and responsibility improve the flow.

These conventions draw on the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html), [google-java-format's AOSP option](https://github.com/google/google-java-format), and Martin Fowler's [Extract Function](https://refactoring.com/catalog/extractFunction.html) and [Change Variable Name](https://refactoring.com/catalog/changeVariableName.html) refactorings. Formatting makes layout consistent; small, behavior-preserving refactorings make the flow easier to understand.
