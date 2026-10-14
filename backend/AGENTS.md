# Java Coding Agent Guidelines

These instructions apply to Java code written, modified, or reviewed in this repository.
The goal is code that another engineer can understand and safely change without
having to reconstruct the author's reasoning.

## 1. Priorities

When making engineering decisions, favor:

1. Correctness and preservation of existing behavior.
2. Readability and explicit intent.
3. Simplicity and maintainability.
4. Consistency with the repository.
5. Reuse only when it reduces overall complexity.

**Optimize for the reader, not the writer.** Do not optimize for fewer lines,
clever syntax, theoretical extensibility, or pattern usage at the expense of clarity.
Treat numeric thresholds below as review signals, not reasons for mechanical refactoring.

## 2. Before changing code

- Read the affected code, nearby tests, and relevant project conventions first.
- Match the project's Java version, frameworks, naming, formatting, and architecture.
- Prefer the smallest change that solves the requested problem.
- Preserve public contracts, behavior, error semantics, and compatibility unless the
  task explicitly requires changing them.
- Do not rewrite unrelated code, introduce dependencies, or add architectural layers
  solely to make a local change.

## 3. Keep control flow shallow

- Aim for no more than **2 levels of control-flow nesting**; treat **3 levels as a
  strong refactoring signal**. This counts nested `if`, `for`, `while`, `switch`,
  and similar blocks, not indentation spaces.
- Prefer guard clauses and early returns for invalid or terminal cases.
- Avoid long `if/else if/else` chains when clearer alternatives exist.
- Extract complex branches into well-named methods when doing so improves the flow.
- Do not force early returns if they harm correctness, resource handling, or clarity.

**Avoid:**

```java
if (order != null) {
    if (order.isPaid()) {
        if (!order.isCancelled()) {
            ship(order);
        }
    }
}
```

**Prefer:**

```java
if (order == null || !order.isPaid() || order.isCancelled()) {
    return;
}
ship(order);
```

## 4. Make conditions explain themselves

- A reader should understand *what decision is being made* without mentally
  decoding a long boolean expression.
- Name non-obvious conditions with descriptive booleans or domain methods.
- Prefer positive, direct names such as `isEligible`, `canRefund`, or
  `requiresManualReview` over ambiguous flags or double negatives.
- Keep simple conditions inline; do not extract every trivial comparison.
- Do not hide side effects inside boolean predicates.

**Avoid:**

```java
if (order.total().compareTo(limit) > 0 && order.isPaid()
        && !order.isBlocked() && customer.isVerified()) {
    submitForReview(order);
}
```

**Prefer:**

```java
boolean exceedsLimit = order.total().compareTo(limit) > 0;
boolean isEligible = order.isPaid() && !order.isBlocked()
        && customer.isVerified();
boolean requiresManualReview = exceedsLimit && isEligible;

if (requiresManualReview) {
    submitForReview(order);
}
```

If a condition represents a reusable business rule, prefer a well-named method
rather than repeating the predicate.

## 5. Write focused, readable methods

- A method should have one clear responsibility or express one coherent workflow.
- At the call site, the main workflow should read at a meaningful level of abstraction.
- Extract logical steps when their names clarify intent or when they are independently
  testable or reusable.
- Review methods that grow beyond roughly **30 lines**; pay particular attention
  around **40–50 lines**. These are prompts for judgment, not hard limits.
- Avoid excessive tiny wrappers, fragmented call chains, and jumping between files
  merely to follow a straightforward operation.
- Prefer straightforward loops to complicated stream chains; use streams when they
  are clearly easier to read and have no surprising side effects.

## 6. Choose meaningful names

- Prefer domain language and precise method, class, and variable names.
- Methods should communicate actions or decisions: `calculateRefund`, `canCancel`,
  `loadPendingOrders`.
- Avoid vague names such as `process`, `handle`, `data`, `flag`, `helper`, or
  `doStuff` unless the surrounding domain makes the meaning unambiguous.
- Prefer names over explanatory comments; avoid unnecessary abbreviations.
- Keep related operations at a consistent level of abstraction.

## 7. Apply DRY pragmatically

- Avoid duplication of **business rules**, validations, and behavior that must
  evolve consistently across callers.
- Do not abstract code solely because two snippets look similar.
- A little local duplication is preferable to a confusing abstraction or a method
  with many boolean flags, optional parameters, or branches for unrelated cases.
- Before sharing logic, confirm that the duplicated pieces represent the same
  domain concept and are likely to change for the same reason.
- Prefer explicit, separate implementations when the cases are genuinely different.
- If an abstraction becomes harder to read than the original code, simplify or
  remove it rather than adding another abstraction.

## 8. Avoid overengineering

- Use the simplest design that meets current requirements.
- Do not introduce interfaces, abstract base classes, factories, strategies,
  generic frameworks, or extra layers without a concrete need.
- Do not build hypothetical flexibility or configuration for unrequested use cases.
- Prefer explicit code over reflection, excessive indirection, or clever tricks.
- Avoid boolean-parameter APIs when named operations express intent more clearly.
- Follow existing architecture where appropriate; do not force a redesign for style.

## 9. Keep side effects and failures clear

- Make I/O, persistence, external calls, and state changes easy to identify.
- Do not swallow exceptions, return misleading success values, or hide failures.
- Catch exceptions where they can be handled meaningfully; preserve useful context.
- Distinguish absent values, expected business rejections, and unexpected failures.
- Avoid unnecessary nulls, mutable shared state, and surprising side effects.
- Preserve transaction boundaries, ordering, idempotency, and concurrency behavior.
- Do not log the same exception repeatedly at multiple layers without a reason.

## 10. Format consistently

- Follow the repository's formatter and established style first.
- For a new repository without conventions, default to Google Java Style Guide:
  consistent 2-space block indentation, 100-character line limit, braces for
  control-flow bodies, standard Java naming, and explicit imports.
- Do not use wildcard imports.
- Prefer automated formatting over manual alignment or subjective spacing changes.
- Keep unrelated formatting changes out of focused feature or bug-fix diffs.

## 11. Comment the reasoning, not the obvious

- Code should communicate *what* happens; comments should explain non-obvious
  *why*, constraints, trade-offs, compatibility, or external system behavior.
- Remove stale or redundant comments.
- Document public contracts where useful, but do not add boilerplate Javadoc
  that merely repeats names and signatures.

## 12. Tests are part of readable code

- When behavior changes, add or update focused tests for the changed behavior.
- Use behavior-oriented test names and a clear Arrange / Act / Assert structure.
- Cover meaningful happy paths, edge cases, and failure cases.
- Avoid excessive mocks and test helpers that obscure the scenario.
- Do not weaken, delete, or skip failing tests to make a change appear successful.
- Run the relevant tests and existing format/static checks when feasible.
  Report honestly what ran, what failed, and what could not be run.

## 13. Final self-review before delivering code

Check the diff against these questions:

- Can someone unfamiliar with this code follow the main flow on a first read?
- Can complex conditions be understood from their names?
- Is control-flow nesting kept shallow (normally 2, review at 3)?
- Are methods and classes cohesive without excessive fragmentation?
- Did I avoid both unnecessary duplication and unnecessary abstraction?
- Are failures, side effects, and business rules explicit?
- Did I preserve existing behavior and avoid unrelated changes?
- Are relevant tests present, and did I verify what I could?

If a guideline would make the code more complicated, favor the clearer solution.
Briefly explain meaningful trade-offs or deviations in the final change summary.

## Repository-specific application

- This repository uses Java 25 and Spring Boot. Preserve the existing scenario-pack
  architecture and database transaction boundaries.
- Use the pinned Spotless formatter with google-java-format's AOSP style: four-space
  block indentation and formatter-controlled continuation indentation. Section 10's
  two-space default applies only to repositories without an established convention.
- Run `mvn -f backend/pom.xml spotless:apply` for Java formatting and
  `mvn -f backend/pom.xml verify` for tests, formatting validation, and Checkstyle.
- Checkstyle treats braces, explicit imports, and separate declarations/statements
  as errors. Method-length, boolean-complexity, and nesting checks are review warnings,
  not automatic reasons to split a coherent workflow. Its nesting checks measure
  each construct separately; review mixed `if`/loop/`switch` nesting manually.
- Use `npm run format:check` for the repository-wide formatting checks with Java 25
  selected and the documented Python virtual environment active.
- Never edit already-applied Flyway migrations solely to reformat them.
