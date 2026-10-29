# Agentic platform MVP (Java 21 / Spring Boot 3)

Control plane (your UI calls it) + runtime plane (agents execute) + governance.

## Run
    export ANTHROPIC_API_KEY=sk-ant-...
    mvn spring-boot:run                      # H2 in-memory
    mvn spring-boot:run -Dspring-boot.run.profiles=postgres   # Postgres

A demo `kyc-agent` is seeded and published on first start.

## Try it
    curl -X POST localhost:8080/api/runs -H 'content-type: application/json' \
      -d '{"agent":"kyc-agent","input":"Onboard Jane Doe, document D-1, checking account"}'
    # -> PENDING_APPROVAL (open-account is a write tool and autonomy is ACT_WITH_APPROVAL)
    curl localhost:8080/api/approvals
    curl -X POST localhost:8080/api/approvals/1/decision -H 'content-type: application/json' \
      -d '{"approve":true,"reviewer":"alice"}'
    curl 'localhost:8080/api/audit?runId=<runId>'

## API for your UI
| Purpose | Endpoint |
|---|---|
| List agents (latest version) | GET /api/config/agent |
| Version history | GET /api/config/agent/{name}/versions |
| Save as new draft | POST /api/config/agent/{name} (JSON AgentSpec) |
| Publish / rollback | POST /api/config/agent/{name}/versions/{v}/publish |
| Dropdown data | GET /api/tools, GET /api/meta |
| Run | POST /api/runs |
| Approval queue | GET /api/approvals, POST /api/approvals/{id}/decision |
| Audit trail | GET /api/audit?runId= |

AgentSpec fields: description, model, instructions, tools[], autonomy, approvalRequiredFor[], guardrails[].
Set `platform.ui-origin` for CORS.

## Layout
- config/ control plane: versioned config, publish/rollback
- tools/ tool gateway: one ToolHandler bean per enterprise action (replace the mocks)
- governance/ policy engine (tiered autonomy, guardrails), approvals, audit
- runtime/ agent loop with approval pause/resume, orchestrator, model client

## Before production
Auth (OAuth2/OIDC + role-based access on config, approvals and audit), Flyway migrations, secrets manager,
rate and cost limits, async runs + streaming, richer orchestrator (multi-agent plans), eval suites in CI,
OpenTelemetry tracing, tamper-evident audit storage.

## Multi-agent workflows
    mvn spring-boot:run -Dspring-boot.run.profiles=mock     # offline scripted model, no API key needed

    curl -X POST localhost:8080/api/workflows -H 'content-type: application/json' \
      -d '{"input":"Onboard Jane Doe and assess her for a $10,000 loan"}'
    # planner -> kyc-agent runs, pauses at open-account (PENDING_APPROVAL, approvalId in response)
    curl -X POST localhost:8080/api/approvals/1/decision -H 'content-type: application/json' \
      -d '{"approve":true,"reviewer":"alice"}'
    # workflow resumes -> credit-agent runs -> COMPLETED
    curl localhost:8080/api/workflows/1

Pass `"steps":[{"agent":"kyc-agent","task":"..."},{"agent":"credit-agent","task":"..."}]` for a fixed pipeline
instead of planner-chosen agents. POST /api/approvals/{id}/decision now returns `{run, workflow}`.
