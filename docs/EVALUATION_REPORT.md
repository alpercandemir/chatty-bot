# Evaluation report — 10 October 2026

Observed locally on an Apple Silicon Mac with Java 21.0.8, Node 26.9.0, Maven 3.9.16 and H2 2.3 in PostgreSQL compatibility mode. Test/build dependencies are pinned. This report separates scripted results from live-model evidence.

| Check | Observed result |
| --- | --- |
| Supplied D01–D16 journeys | All 16 pass against persistent simulator facts |
| Public-HTTP rehearsal evaluation | 50 / 50 pass: supplied journeys plus documented paraphrases |
| JVM acceptance/safety suite | 27 tests pass, including all 16 fixture journeys |
| Rehearsal whole-journey p95 | 131 ms across 50 runs; includes HTTP polling, reset and confirmation steps; this is not a live-turn benchmark |
| Java package | Builds successfully with Java 21 |
| React TypeScript production bundle | Builds successfully |
| Frontend dependency audit | Zero known vulnerabilities after upgrading Vite to 7.3.7 |
| Docker Compose configuration | Validates successfully |
| Browser checks | Cancellation preview, persisted confirmation after refresh, successful one-time mutation, exact operation reference, responsive customer/presenter layout, fixture reset; real Live AI address intake and direct city follow-up with NS-FAQ-ADDRESS citation |
| Live Gemini smoke | Successful real Gemini tracking turn after fixes: 2 model requests, 2 tool calls, 2,915 reported tokens; model-authored answer contains the carrier, tracking reference, status and returned ETA. Earlier requests timed out with the initial 20-second limit. |
| Key handling | User-supplied key stored in ignored `.env` with mode 0600 and passed only to backend; credential value not in reports/bundles/events |

Real live response evidence: [tracking run](evaluation/live-smoke.json), [city follow-up screenshot](live-verification.jpg).

Machine-readable rehearsal results: [evaluation/latest.json](evaluation/latest.json). Browser verification: [desktop](browser-verification-desktop.jpg), [mobile](browser-verification.jpg).

Safety checks cover: no write before confirmation, typed yes, foreign session/action access, expired/superseded proposals, repeat/concurrent confirmations, duplicate message delivery, two proposals racing on an old version, fulfillment lock, dispatched order, cross-owner/missing resource equivalence, address snapshot isolation, unsupported country, declined action, failure before commit, unknown after commit and receipt-only reconciliation, interrupted-run recovery, presenter-only controls, cross-origin rejection, reset session invalidation provider-error redaction/classification, untrusted knowledge instructions, unregistered tools and forbidden identity arguments, cross-workspace isolation and local synthetic-data purge.

The 50-case HTTP result is rehearsal evidence only. The supplied Gemini key and `gemini-3.8-flash` access have now been verified by a successful metadata request, a tiny generation probe and a tracking run through the official Java SDK. No model switch, paid fallback or billing change was attempted. Live batch evaluation is supplied as a command but has not achieved the PRD's 135/150 target. Usage for these failed requests is unknown, not zero. A direct generation probe took 32.5 seconds. The provider deadline is now 45 seconds and the active run budget 120 seconds, with low reasoning effort and no unsupported candidate-count setting. The PRD 15-second live-read target is not met.

Docker was installed but its daemon was unavailable, and no PostgreSQL server was available. Compose image build/startup, PostgreSQL migrations/GIN retrieval and restart behavior on PostgreSQL therefore remain unverified. Native Java/H2/Vite startup and persisted reload were observed. Complete production-style typed domain/result contracts, an extensible manifest loader, per-handler deadlines for future remote connectors, and the complete adversarial/live evaluation set remain further work. The implementation is a runnable local prototype; this report does not assert complete PRD acceptance or pilot readiness.

Regression coverage added for the reported UX failures: a city follow-up during address intake, pasted walkthrough text rejected as address data, model-authored read/clarification responses preserved, citations retained across multiple reads, and backend-owned confirmation previews. Fixture-driven adapter tests verify response plumbing; the tracking smoke is separate real-provider evidence.

The browser follow-up "Is it really necessary to specify city?" completed with a direct model answer and an approved address-policy citation. The address intake took about 58 seconds of active time; the city follow-up about 57 seconds. Queue time for the first browser request was approximately 37 seconds behind the tracking test. These are individual observations, not a percentile benchmark. Active time now includes the final model response; earlier snapshots recorded only time through the last tool.
