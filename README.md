# Beyond Trading

NPCs that feel alive — with personalities, memories, conversations, and local LLM-powered decision-making, all inside Minecraft 26.2.

This development branch targets Minecraft **26.2**. No GitHub Releases are
currently published; obtain the mod by building this checkout locally. There
is no checked-in CI artifact or release workflow. The broader `>=26.2`
metadata predicate does not establish support for other Minecraft versions.

## What it does

- AI NPCs are actual Minecraft Villager entities with persistent identities that remember interactions and act on them
- Vanilla clients are supported: the server presents agents as ordinary `minecraft:villager` entities, so clients do not need Beyond Trading installed
- Naturally spawned villagers are adopted automatically; existing inventory, trades, vanilla profession, age, gossip, and home/job-site positions are carried into the agent
- Right-click to talk; NPC responds via a local LLM (vLLM or LM Studio)
- NPC asks the local LLM to choose bounded actions (go home, work, wander, socialize, follow, etc.) based on personality and situation
- Memories persist per world as Markdown files
- Needs, long-term priorities, and relationships persist per NPC as JSON; village news is recorded in memories
- Nearby villagers share a live village situation: workforce, food/material pressure, danger, tired residents, and nearby players
- `COORDINATE` lets agents meet, share role-relevant supplies, exchange local knowledge, and form two-way relationships
- Profession work implements farming, trading, repair, bookmaking, and gathering dropped items, subject to nearby resources and interaction requirements
- Designed for local LLM backends; no hosted service is required. Keep the configured endpoint local for this setup

### Local model context

Autonomous decisions use an approximate 6,000-token context budget;
conversations use 4,500. Structured replies allow up to 4,096 completion
tokens with reasoning disabled, or 8,192 with reasoning enabled (including
hidden reasoning). Plain `/ask` replies allow 2,048 tokens without reasoning.
Configure the local model's context window to accommodate both the prompt and
completion budget; approximate prompt sizing and message overhead also matter.
Identity, live world state, needs, and goals take priority over older memories
when prompt context is full. These are code limits, not measured model quality
or hardware recommendations.

The generated config uses `reasoningEffort: "none"` by default for fast,
reliable structured actions. Set it to `on` for Qwen-style thinking models
(or `off` to send `none` to the backend), or use `minimal`, `low`, `medium`,
`high`, or `xhigh` when the selected backend supports those OpenAI-style
efforts. The mod tries the model's configured reasoning value first, then a
compatible fallback for `on`, and finally retries with the optional reasoning
field omitted if requests fail. Empty-content responses also have retries with
`reasoning_effort: "none"`. Backend behavior determines whether thinking is
disabled. Thinking requests may be substantially slower.

## Quick start

1. Build/install as described below, or use the development server command in step 2. Start a local OpenAI-compatible LLM server (vLLM or LM Studio) with a model loaded
2. For development, start the Fabric server with `./gradlew runServer --args="--nogui"`
3. Complete the normal server EULA/setup before joining `localhost:25565` using an unmodded Minecraft 26.2 client
4. With operator/game-master command permission, run `/beyondtrading summon` — or let a naturally spawned villager be adopted
5. Right-click an agent to talk; use shift-right-click for the normal villager trade screen

After spawning, Thomas begins making autonomous decisions shortly afterward. The model receives his personality, profession, memories, needs, long-term goal, time of day, biome, weather, nearby entities, home, workplace, and shared village pressure. If the LLM is unavailable, he falls back to a safe home/work/wander routine until the server becomes available again.

For a manual smoke test that bypasses model action selection, place two AI villagers nearby and run `/beyondtrading action <entity> COORDINATE`, then `/beyondtrading inspect <entity>`. Allow time for the agents to meet within three blocks; inspect both for coordination memories, outcomes, and relationships. Navigation or missing neighbors can prevent completion. Use `GO_TO_WORK`, `SLEEP`, or `RESTOCK_MATERIALS` the same way to exercise individual world actions without relying on a particular model response.

### Vanilla-client verification checklist

The vanilla entity identity supports clients without the mod. The following is
a manual checklist, not a record of a connected-client test pass. With a
dedicated server running:

1. Connect to `localhost:25565` with an unmodded client. From the server console, grant permission with `op <your-name>`, then use `/gamemode creative` in-game if desired.
2. Right-click an AI villager normally, type a message in chat, and confirm the reply uses the villager’s name. Say `bye` to close the session; `/beyondtrading inspect <villager>` should show a conversation memory and a player relationship.
3. Shift-right-click the same villager to open the native vanilla trade screen. Complete a trade, then inspect it again; the trade memory and relationship familiarity should increase.
4. Summon two nearby roles, for example `/beyondtrading summon ~ ~ ~ Ada farmer` and `/beyondtrading summon ~ ~ ~ Bram merchant`. Run `/beyondtrading action <one-entity> COORDINATE`, then inspect both entities. The receiver should have a coordination memory/outcome and an NPC relationship.
5. Run `/beyondtrading verify` from the server console. A healthy result is `PASS`, requiring at least one agent, zero unadopted villagers, healthy tracked brains, vanilla network identity, and a detected local LLM backend. Detection reflects the model-list probe; `PASS` does not prove a successful completion or client interaction.

## Requirements

- Minecraft 26.2 (Java Edition)
- Fabric Loader 0.19.3+ (build dependency: 0.19.3)
- Fabric API (build dependency: 0.155.0+26.2)
- Java 25
- A local LLM server at `localhost:8000` (vLLM) or `localhost:1234` (LM Studio)

The server-side mod is required where the world runs. Players may connect with
an unmodded vanilla client; they will see AI agents as normal villagers.

## Local build and installation

Use Java 25 and the included Gradle wrapper (Gradle 9.6.1):

```sh
./gradlew build -x copyToServer
```

The configured jar output is `build/libs/beyond-trading-0.1.0.jar`. The version
is a development build version, not a published release. Put the jar and the
matching Fabric API in your Minecraft 26.2 Fabric server's `mods/` directory.
For single-player use, install them in the local Fabric instance that runs the
world. Remote players do not need Beyond Trading installed.

The `-x copyToServer` flag skips the build finalizer that otherwise copies the
jar to the machine-specific `serverModsDir` in `gradle.properties`.
`./gradlew test` runs the JUnit simulation contract tests without starting a
Minecraft server. Those tests cover parsing, bounded state, prompt budgets,
and memory persistence; they do not establish multiplayer gameplay behavior.
On 2026-10-24, `./gradlew --no-daemon --max-workers=1 --console=plain build -x copyToServer` passed and produced `build/libs/beyond-trading-0.1.0.jar`; all 20 JUnit tests passed. This verifies compilation, packaging, and simulation contracts, not LLM-backed or connected-client gameplay. The `copyToServer` finalizer was skipped; no server copy or deployment occurred.

## Configuration and saved data

First initialization creates `config/beyondtrading.json` relative to the
Fabric instance. Its `llm` fields are `baseUrl`, `apiKey`, `model`, `timeoutMs`,
and `reasoningEffort`. Defaults use `http://localhost:8000/v1`, model
`Qwen/Qwen2.5-9B-Instruct`, a 120,000 ms timeout, and reasoning `none`.
Set the model to an identifier actually served by your local backend.
The mod probes the configured endpoint, then local ports 1234 and 8000 via
`/v1/models`; requests use `/v1/chat/completions`. If the configured model is
absent from the detected list, requests use the first sorted available model.
Edit the file while stopped and restart to load changes; the model commands
below save their changes immediately.

Per-world memories live in `beyondtrading/memories/<uuid>.md`; identity, needs,
goals, relationships, and action outcomes live in
`beyondtrading/npcdata/<uuid>.json` under the world directory.

## Commands

`/beyondtrading` requires game-master command permission. The source does not
add that permission requirement to `/ask` or `/model`, including model changes.
Use an entity UUID or a selector resolving to one agent for `<entity>`.

| Command | Description |
|---------|-------------|
| `/beyondtrading summon [<pos>] [<name>] [<profession>]` | Spawn an AI NPC (default: Thomas); position is three coordinates |
| `/beyondtrading inspect <entity>` | Show NPC personality, goals, memories |
| `/beyondtrading decide <entity>` | Request an immediate LLM decision |
| `/beyondtrading action <entity> <action>` | Start one bounded action without LLM selection; completion depends on world state |
| `/beyondtrading config` | Show current LLM configuration |
| `/beyondtrading verify` | Read-only lifecycle check for loaded villagers and agent state |
| `/ask <message>` | Ask the local model a player-facing question |
| `/model` | Show the configured model |
| `/model <name>` | Change the active model |
| `/model refresh` | Re-probe backend endpoints and refresh model suggestions |
| `/model reasoning <effort>` | Set reasoning (`none`, `off`, `on`, `minimal`, `low`, `medium`, `high`, `xhigh`) |

## License

MIT
