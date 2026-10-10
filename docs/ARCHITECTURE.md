# Architecture

One backend owns the agent loop, synthetic business systems and transaction boundaries. ScenarioPack implementations register tools, schemas, policies, rehearsal planning and execution. Harness and Actions never branch on a commerce/cleaning pack name; an architecture test enforces this boundary.

## Persistence and concurrency

Flyway creates `demo_workspace` and `demo_session`. Each presenter receives two isolated workspace documents; all conversations, messages, runs, events, actions, operation receipts and domain records persist in the workspace document. Domain adapters supply success templates; Actions renders generic lifecycle outcomes without industry branches. A SQL `SELECT ... FOR UPDATE` lock serializes each command. JSON documents are a deliberate local-prototype simplification: transactional consistency is implemented, while normalized conversation/action tables remain future work. Approved knowledge also persists in a scoped relational table, with a PostgreSQL GIN full-text index; H2 uses the deterministic topic fallback.

The same transaction checks ownership, expected resource version and domain policy, applies a mutation, saves its operation receipt and appends audit events. Duplicate action confirmation returns the saved result before rechecking the now-changed resource version. Client message IDs deduplicate run creation; a QUEUED-to-RUNNING claim prevents two workers from executing the same run. Proposals bind server identity, conversation, exact normalized args, policy version, resource version and expiry. Parameter updates supersede the old action. Textual yes directs users to the card and cannot execute.

Backend-rendered proposal/outcome messages are authoritative. Live final answers on tool turns use the retrieved backend result. Unsourced non-clarification completions receive an evidence-limitation template. A separate operation receipt distinguishes authoritative simulator commit from harness knowledge. Injected after-commit timeout leaves the ledger UNKNOWN, blocks changes to that resource and blocks reset. Reconciliation reads the saved receipt and never invokes a write again.

## Runs and provider

Virtual-thread workers process serial tool calls with six model requests, ten total tool attempts, a 120-second active budget and a 45-second provider timeout. Live runs share a fair semaphore with concurrency one. Queue time is separate from active processing. Model usage is reported only when supplied. Provider 400/401/403, 404, 429 and transport errors are reported separately without credentials or raw provider payloads. Parallel model function calls are queued and executed serially; the entire returned Content (including opaque continuation parts) is saved internally. Provider checkpoints are excluded from public snapshots.

On startup, orphaned QUEUED/RUNNING runs become FAILED with a recovery event, clearing the active slot. Waiting proposals survive restart; expiration is checked on conversation reads/new messages and confirmation. The local adapter commits ledger and domain state together, so it cannot leave a partially committed EXECUTING write. An external connector would need its own durable dispatch/status reconciliation implementation.

## Events and identity

Events persist before SSE delivery. SSE replays numbered events using Last-Event-ID or `after`, then sends a current conversation snapshot. Reconnect reads current state and event IDs; the UI renders events from the current persisted snapshot, avoiding duplicate timeline entries. The read-only history never re-executes tools. Contact/address fields and customer-rendered message strings are redacted in event detail.

Separate HttpOnly, SameSite=Strict presenter/customer cookies protect controls and customer identity. Customer access checks the server-recorded principal, session and workspace generation. Reset replaces only that presenter's selected pack and invalidates its old customer sessions/proposals. Loopback Host/Origin checks protect the intentionally unauthenticated local presenter bootstrap. This local demo access model must be replaced before a pilot.

## Limits still requiring validation

PostgreSQL Compose startup remains unverified. A real Java SDK tracking turn is now verified; broader live accuracy remains unmeasured. PostgreSQL full-text retrieval is implemented but has not been exercised against a running PostgreSQL instance here. Tool manifests expose version, arguments, result-object contract, classification, authorization, retry semantics and a five-second deadline. JDBC queries have a five-second timeout; trusted in-process adapters are synchronous. Fully typed domain/result contracts, configurable handler deadlines and a full provider-failure injection suite remain follow-up work. Live invalid arguments receive one repair attempt within the original budgets. No 90% live accuracy claim is made.

Live read responses and clarification text now preserve Gemini wording. A provider-level `respond_to_customer` function supplies text and an explicit input-wait flag. This is handled inside the model adapter, never dispatched to business adapters. Sources are attached from all reads in the turn. Backend policy denials, unsupported retrieval, saved action previews and operation outcomes bypass model wording. The provider uses low thinking effort and omits the unsupported candidate-count setting.
