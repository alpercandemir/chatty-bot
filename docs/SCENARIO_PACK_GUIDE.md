# Add a scenario pack

Implement `ScenarioPack` in the `scenario` package and register it as a Spring component. Supply an ID, business instructions, an allowlist of JSON argument schemas, a scripted planner, tool handlers and confirmed-operation execution. Every run records pack, policy and tool-schema versions. The current versions are 1.0.0.

Tools may retrieve facts or call `ToolContext.propose`. A preparation tool never applies a business change. Shared Actions executes a saved proposal only after a customer confirms its ID; the pack then revalidates domain state and returns an authoritative result. Supply all authorization checks through server context; do not accept customer/workspace identity from model args. A domain change must return before/after data or a persisted request reference.

Add scoped fixtures/approved knowledge and demo identities, then add journeys to `demo/scenarios.json`. Include denied, incomplete, cross-owner, stale, duplicate-confirmation and unknown-outcome cases. Teach the frontend any specialized card it needs; the harness remains unchanged. No arbitrary code/URL/tool registration can be introduced by customers or knowledge content.

Versioned manifests are available from `GET /api/v1/demo/packs`. They expose business instructions, registered adapter bindings, knowledge/fixture/evaluation references and tool contracts. The components are server-registered rather than externally hot-loadable. Adding a third pack currently also requires extending demo session selection/seed mapping; dynamic pack onboarding and a fully versioned external manifest loader are not implemented.
