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

## Model provider and database profiles
The database and the model provider are chosen independently.

| SPRING_PROFILES_ACTIVE | Database | Model |
|---|---|---|
| (none) | H2 in memory | Anthropic |
| `postgres` | Postgres | Anthropic |
| `postgres,openai` | Postgres | OpenAI / OpenAI-compatible |
| `postgres,mock` | Postgres | scripted offline mock |

Keys: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`. For a local OpenAI-compatible server (Ollama, vLLM) set
`OPENAI_BASE_URL=http://host:11434/v1` and `OPENAI_MODEL=llama3.1`; no key is needed for non-OpenAI hosts.
The old `progres` profile is no longer used.

## Workflow definitions vs runs
- Definition (designed, versioned): `/api/config/workflow/...`, same list / draft / versions / publish endpoints as agents.
  Spec: `{"description": "...", "steps": [{"agent": "kyc-agent", "task": ""}]}`. Publishing requires every agent to be published.
- Run (an instance): `POST /api/workflows` (alias `/api/process-runs`) with `{"input": "...", "definition": "onboard-and-credit-review"}`.
  Runs record the definition name and version. Without `definition`, send `steps` or let the planner choose.

## Workflow engine: local or Temporal
`platform.workflow.engine` (env `WORKFLOW_ENGINE`) selects how runs execute. The REST API, `WorkflowResult` shape and
the `WorkflowRun` table are identical for both, so the UI does not change.

| | `local` (default) | `temporal` |
|---|---|---|
| Execution | In-process loop (`WorkflowService`) | Durable workflow on a Temporal cluster (`TemporalWorkflowEngine`) |
| Survives restarts mid-run | No | Yes — history is replayed, in-flight step retried |
| Approval wait | Row in DB, resumed by the HTTP call | Durable `Workflow.await`, no timeout; decision delivered as a Signal |
| Infra | none | Temporal server + UI (`docker-compose.temporal.yml`) |

Start Temporal next to your existing Postgres (creates the `temporal` and `temporal_visibility` databases):
```bash
docker compose -f docker-compose.temporal.yml up -d     # server :7233, UI http://localhost:8233
WORKFLOW_ENGINE=temporal TEMPORAL_TARGET=localhost:7233 mvn spring-boot:run
```
In Docker Compose for the app add `WORKFLOW_ENGINE=temporal` and `TEMPORAL_TARGET=temporal:7233`.
Each run is workflow id `wf-{id}` on task queue `agentic-workflows`; the worker is embedded in this JVM
(`TEMPORAL_WORKER_ENABLED=false` on API-only replicas). All LLM/agent/DB work runs in Activities
(5 min step timeout, 3 attempts); the workflow itself is deterministic. Runs started under one engine must be
resumed under the same engine (`WorkflowRun.engine`).

## Tools over MCP (Model Context Protocol)
Tools are plain `ToolHandler` beans (CBS, database, Kafka adapters). MCP makes them reusable in both directions.

**Expose the platform's tools to other agents/frameworks** - `POST /mcp` (Streamable HTTP, JSON responses, stateless).
Off by default and fails closed:
    MCP_SERVER_ENABLED=true
    MCP_SERVER_TOKEN=<long random secret>      # clients send: Authorization: Bearer <token>
- Write tools (those with `writes() == true`) are hidden and uncallable unless `platform.mcp.server.allow-writes: true`.
- Requests carrying an `Origin` header are rejected unless listed in `platform.mcp.server.allowed-origins`.
- Every call is written to the audit trail under a run id `mcp-<uuid>`, agent `mcp-client`.
- Calls through `/mcp` do not pass through an agent's autonomy/approval rules. Keep allow-writes off unless the caller is trusted.
- Put it behind TLS (nginx) before exposing it beyond localhost.

**Use tools served by other MCP servers** (for example a CBS or Kafka adapter written in any language):
    platform:
      mcp:
        servers:
          - name: cbs
            url: http://cbs-mcp:8000/mcp
            token: ${CBS_MCP_TOKEN:}
            prefix: cbs            # tools become cbs-<toolname>
Discovered tools join the tool gateway and appear in `GET /api/tools`. A remote tool counts as a write tool unless the
server marks it `readOnlyHint: true`, so autonomy and approval rules apply. `GET /api/mcp/servers` shows connection status and
`POST /api/mcp/servers/refresh` reconnects. A server that is down never blocks startup.
Known limits: no stdio transport, no server-initiated messages, no automatic re-initialize if a remote session expires.

## Tests
`mvn verify` runs unit tests for the MCP server and client (including a loopback HTTP round trip), the OpenAI message
translation, and the policy engine. CI now runs them before deploying.
