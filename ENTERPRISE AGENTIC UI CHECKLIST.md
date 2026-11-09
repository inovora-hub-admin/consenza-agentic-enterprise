# Enterprise Agent UI Checklist (Evidence-Backed)

Status key: `Implemented` | `Partial` | `Planned`

## Workflow — `Partial`
- [x] The agent has a narrow, named workflow.
  - Evidence: `/v1/screen` (screening) and `/v1/profiles/{profile_id}` (profile drilldown).
- [ ] The trigger, inputs, actions, exceptions, and owner are defined.
  - Gap: owner, exception routing, and trigger policy are not represented in current service contracts.
- [ ] Success metrics are tied to business outcomes.
  - Gap: business KPI mapping (for example false positive rate, review SLA, conversion impact) is not defined in API or UI contract.

## Autonomy — `Planned`
- [ ] Autonomy is scoped by action type, user role, data class, and risk.
  - Gap: no autonomy policy object is exposed by current services.
- [ ] The current autonomy mode is visible to the user.
  - Gap: `autonomy_mode` is set during Agent Creation; orchestration must surface the effective runtime mode in UI/trace.
- [ ] High-risk actions require approval.
  - Gap: no approval gate contract in current APIs.

## Context — `Partial`
- [x] The UI shows which sources were used.
  - Evidence: `external_sources` in profile details.
- [x] Source-level citations or evidence are available.
  - Evidence: `reasons`, `external_sources`, and detailed profile payload.
- [ ] Missing, stale, or conflicting context is surfaced.
  - Gap: no explicit stale/conflict flags.
- [ ] Access control follows existing enterprise permissions.
  - Gap: only API key auth is visible from available data.

## Permissions — `Planned`
- [ ] Read and write permissions are separate.
  - Gap: no user-scoped RBAC/ABAC model is exposed.
- [ ] Consent prompts explain data, action, reason, and duration.
  - Gap: no consent object or prompt lifecycle in service contracts.
- [ ] Permissions are time-bound where possible.
  - Gap: no time-bound grant model exposed.

## Approvals — `Planned`
- [ ] Proposed actions show before/after state.
  - Gap: current services are read/screen workflows; no mutation proposal envelope.
- [ ] The reviewer can inspect evidence and policy matches.
  - Gap: evidence exists, but there is no reviewer workflow or policy decision artifact.
- [ ] Approval history is stored.
  - Gap: no approval history endpoint/event model.
- [ ] Bulk approval does not hide meaningful differences.
  - Gap: no bulk approval feature in current services.

## Verification — `Partial`
- [ ] The task has a definition of done.
  - Gap: definition-of-done criteria are not formalized in workflow contract.
- [x] Required checks run before execution.
  - Evidence: match scoring and threshold evaluation (`score`, `threshold_used`) occur during screening.
- [ ] Failures block or escalate the workflow.
  - Gap: no explicit block/escalate state machine exposed.
- [ ] Dry-run or simulation exists for risky actions.
  - Gap: no simulation mode contract.

## Memory — `Planned`
- [ ] Memory is visible, editable, scoped, and deletable.
  - Gap: no user/agent memory subsystem is exposed.
- [ ] Durable memory has provenance.
  - Gap: dataset provenance exists for profiles, but not agent memory provenance.
- [ ] Sensitive workflows can disable memory.
  - Gap: no memory policy toggle exists.

## Audit — `Partial`
- [x] Agent identity, tools, sources, approvals, and actions are logged.
  - Evidence: `request_id` enables request-level trace correlation; sources are present in profile details.
  - Gap: actor identity, tool invocation logs, approval logs, and action ledger are not visible yet.
- [ ] Logs are reviewable by the right roles.
  - Gap: no role-scoped audit access model is represented.
- [ ] Retention and redaction rules are defined.
  - Gap: no retention/redaction policy contract is exposed.

## Recovery — `Planned`
- [ ] Users can pause, cancel, retry, escalate, or roll back.
  - Gap: synchronous request-response APIs do not expose workflow controls.
- [ ] Partial success is visible.
  - Gap: no partial-success state model for multi-step orchestration.
- [ ] Error messages identify the failed system or policy.
  - Gap: service errors are not standardized to policy/system taxonomies in current evidence.

## Operations — `Planned`
- [ ] Long-running tasks have durable state.
  - Gap: no async job/state endpoints are exposed.
- [ ] Notifications are tied to human decisions.
  - Gap: no decision-driven notification hooks are shown.
- [ ] Owners can monitor queues and stuck tasks.
  - Gap: no queue or stuck-task observability contract is exposed.

## Next API/Service Additions (Priority Order)
1. Add decision envelope fields to screening/orchestration outputs: `risk_level`, `policy_result`, `requires_approval` (`autonomy_mode` comes from Agent Creation and should be surfaced as effective runtime mode).
2. Add approval workflow service: propose, review, approve/reject, immutable history.
3. Add audit event service keyed by `request_id`, `actor_id`, `tool_id`, and decision ID.
4. Add async operations endpoints: task submit/status/retry/cancel plus queue metrics.
5. Add permission and consent model: scoped roles, time-bound grants, and consent receipts.

## Control Plane vs Data Plane
- **Control Plane**: Agent lifecycle and governance controls, including Agent Creation (`autonomy_mode`), permissions/consent, approvals, policy decisions, audit retention/redaction, and operational controls.
- **Data Plane**: Screening and profile retrieval execution paths, including `/v1/screen`, `/v1/profiles/{profile_id}`, match scoring, profile evidence, and request correlation fields.
- **Runtime contract**: Orchestration must combine control-plane policy (for example effective autonomy mode and approval requirements) with data-plane outcomes (matches, reasons, sources) before user-facing actions.

## Execution Tracker (Owner + Target Date)
Use this table to assign implementation ownership and delivery dates for all `Partial` and `Planned` controls.

| Priority | Area | Control | Status | Owner | Target Date | Notes |
|---|---|---|---|---|---|---|
| P1 | Workflow | Trigger, inputs, actions, exceptions, owner defined | Partial | Platform Team | YYYY-MM-DD | Add workflow contract and exception routing |
| P2 | Workflow | Success metrics tied to business outcomes | Partial | Platform Team | YYYY-MM-DD | Define KPI set and reporting path |
| P1 | Autonomy | Scope by action type, role, data class, risk | Planned | Platform Team | YYYY-MM-DD | Publish autonomy policy object |
| P1 | Autonomy | Current autonomy mode visible to user | Planned | Platform Team | YYYY-MM-DD | Surface effective runtime mode in orchestration trace |
| P0 | Autonomy | High-risk actions require approval | Planned | Platform Team | YYYY-MM-DD | Add approval gate in policy engine |
| P1 | Context | Missing/stale/conflicting context surfaced | Partial | Platform Team | YYYY-MM-DD | Add context quality flags |
| Deferred | Context | Enterprise access control enforced | Partial | Platform Team | YYYY-MM-DD | Out of current phase (permissions/consent scope excluded) |
| Deferred | Permissions | Read/write permissions separated | Planned | Platform Team | YYYY-MM-DD | Out of current phase (permissions/consent scope excluded) |
| Deferred | Permissions | Consent prompts with reason/duration | Planned | Platform Team | YYYY-MM-DD | Out of current phase (permissions/consent scope excluded) |
| Deferred | Permissions | Time-bound permissions | Planned | Platform Team | YYYY-MM-DD | Out of current phase (permissions/consent scope excluded) |
| P0 | Approvals | Before/after proposed actions shown | Planned | Platform Team | YYYY-MM-DD | Add mutation proposal envelope |
| P0 | Approvals | Reviewer sees evidence + policy match | Planned | Platform Team | YYYY-MM-DD | Add policy decision artifact in review UI |
| P0 | Approvals | Approval history stored | Planned | Platform Team | YYYY-MM-DD | Add immutable approval event log |
| P1 | Approvals | Bulk approval preserves differences | Planned | Platform Team | YYYY-MM-DD | Add diff-aware bulk review controls |
| P1 | Verification | Definition of done per task | Partial | Platform Team | YYYY-MM-DD | Add DoD criteria in workflow spec |
| P0 | Verification | Failures block/escalate workflow | Partial | Platform Team | YYYY-MM-DD | Add block/escalate state machine |
| P2 | Verification | Dry-run/simulation for risky actions | Planned | Platform Team | YYYY-MM-DD | Add simulation mode in orchestration |
| P2 | Memory | Memory visible/editable/scoped/deletable | Planned | Platform Team | YYYY-MM-DD | Add memory management controls |
| P2 | Memory | Durable memory provenance | Planned | Platform Team | YYYY-MM-DD | Add provenance metadata for memory entries |
| P2 | Memory | Memory disable toggle for sensitive flows | Planned | Platform Team | YYYY-MM-DD | Add per-workflow memory policy switch |
| P0 | Audit | Full actor/tool/approval/action logs | Partial | Platform Team | YYYY-MM-DD | Extend beyond request_id correlation |
| Deferred | Audit | Role-based log review | Planned | Platform Team | YYYY-MM-DD | Depends on permissions scope; defer with permissions workstream |
| P1 | Audit | Retention + redaction rules | Planned | Platform Team | YYYY-MM-DD | Add policy and enforcement jobs |
| P1 | Recovery | Pause/cancel/retry/escalate/rollback controls | Planned | Platform Team | YYYY-MM-DD | Add orchestration control endpoints |
| P1 | Recovery | Partial success visibility | Planned | Platform Team | YYYY-MM-DD | Add step-level execution outcomes |
| P1 | Recovery | Errors mapped to failed system/policy | Planned | Platform Team | YYYY-MM-DD | Standardize error taxonomy |
| P1 | Operations | Durable state for long-running tasks | Planned | Platform Team | YYYY-MM-DD | Add async task state store |
| P2 | Operations | Notifications tied to human decisions | Planned | Platform Team | YYYY-MM-DD | Add decision event notifications |
| P1 | Operations | Queue/stuck task monitoring | Planned | Platform Team | YYYY-MM-DD | Add queue metrics and stuck-task alerts |

## Current Scope (This Phase)
- In scope: approvals, autonomy runtime visibility, verification/block-escalate, audit completeness, recovery controls, operations durability, memory governance, workflow contract/DoD, and context quality flags.
- Deferred: permissions and consent model, plus context access-control enforcement and any capability that depends on those controls.
