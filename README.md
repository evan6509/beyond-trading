# Beyond Trading

NPCs that feel alive — with personalities, memories, conversations, and local LLM-powered decision-making, all inside Minecraft 26.2.

## What it does

- AI NPCs are actual Minecraft Villager entities with persistent identities that remember interactions and act on them
- Vanilla clients are supported: the server presents agents as ordinary `minecraft:villager` entities, so clients do not need Beyond Trading installed
- Naturally spawned villagers are adopted automatically; existing inventory, trades, profession, age, and village memories are carried into the agent
- Right-click to talk; NPC responds via a local LLM (vLLM or LM Studio)
- NPC asks the local LLM to choose bounded actions (go home, work, wander, socialize, follow, etc.) based on personality and situation
- Memories persist per world as Markdown files
- Needs, long-term priorities, relationships, and local village knowledge persist per NPC
- Nearby villagers share a live village situation: workforce, food/material pressure, danger, tired residents, and nearby players
- `COORDINATE` lets agents meet, share role-relevant supplies, exchange local knowledge, and form two-way relationships
- Profession work has concrete world effects: farming, trading, repair, bookmaking, and gathering
- No cloud APIs — everything runs on your machine

### Local model context

For LM Studio, a **16k-token model context** is a good starting point. The mod
keeps the actual prompts smaller: autonomous decisions use an approximate 6k
token context budget, conversations use 4.5k, and the response/reasoning
budget is 4k tokens. Identity, current danger, needs, goals, and the active
relationship are kept ahead of older memories when a prompt is full. This
keeps thinking-capable models coherent without making every decision slower
than necessary. Use 8k if memory is tight; move to 32k only when the village
has substantially richer histories.

The generated config uses `reasoningEffort: "none"` by default for fast,
reliable structured actions. Set it to `on` for Qwen-style thinking models
(or `off` to explicitly disable thinking), or use `minimal`, `low`, `medium`,
`high`, or `xhigh` when the selected backend supports those OpenAI-style
efforts. The mod tries the model's configured reasoning value first, then a
compatible fallback, and finally retries with reasoning disabled if the local
server rejects the optional setting. Thinking requests may be substantially
slower.

## Quick start

1. Start your LLM server (vLLM or LM Studio)
2. Start the Fabric server with `./gradlew runServer --args="--nogui"`
3. Join `localhost:25565` using an unmodded vanilla client
4. Run `/beyondtrading summon` — or let a naturally spawned villager be adopted
5. Right-click an agent to talk; use shift-right-click for the normal villager trade screen

After spawning, Thomas begins making autonomous decisions shortly afterward. The model receives his personality, profession, memories, needs, long-term goal, time of day, biome, weather, nearby entities, home, workplace, and shared village pressure. If the LLM is unavailable, he falls back to a safe home/work/wander routine until the server becomes available again.

For a deterministic smoke test, place two AI villagers nearby and run `/beyondtrading action <entity> COORDINATE`, then `/beyondtrading inspect <entity>`. The inspect output should show a completed coordination outcome, increased village knowledge, and a relationship with the neighboring villager. Use `GO_TO_WORK`, `SLEEP`, or `RESTOCK_MATERIALS` the same way to exercise individual world actions without relying on a particular model response.

### Vanilla-client verification checklist

The client does not need the mod. With the dedicated server running:

1. Connect to `localhost:25565` with an unmodded client. From the server console, grant permission with `op <your-name>`, then use `/gamemode creative` in-game if desired.
2. Right-click an AI villager normally, type a message in chat, and confirm the reply uses the villager’s name. Say `bye` to close the session; `/beyondtrading inspect <villager>` should show a conversation memory and a player relationship.
3. Shift-right-click the same villager to open the native vanilla trade screen. Complete a trade, then inspect it again; the trade memory and relationship familiarity should increase.
4. Summon two nearby roles, for example `/beyondtrading summon ~ ~ ~ Ada farmer` and `/beyondtrading summon ~ ~ ~ Bram merchant`. Run `/beyondtrading action <one-entity> COORDINATE`, then inspect both entities. The receiver should have a coordination memory/outcome and an NPC relationship.
5. Run `/beyondtrading verify` from the server console. It must report `PASS`, zero unadopted villagers, healthy tracked brains, vanilla network identity, and an available local LLM backend.

## Requirements

- Minecraft 26.2 (Java Edition)
- Fabric Loader 0.19.3+
- Java 25
- A local LLM server at `localhost:8000` (vLLM) or `localhost:1234` (LM Studio)

The server-side mod is required where the world runs. Players may connect with
an unmodded vanilla client; they will see AI agents as normal villagers.

## Commands

| Command | Description |
|---------|-------------|
| `/beyondtrading summon [<pos>]` | Spawn an AI NPC |
| `/beyondtrading inspect <entity>` | Show NPC personality, goals, memories |
| `/beyondtrading decide <entity>` | Request an immediate LLM decision |
| `/beyondtrading action <entity> <action>` | Deterministically exercise one bounded action |
| `/beyondtrading config` | Show current LLM configuration |
| `/beyondtrading verify` | Read-only lifecycle check for loaded villagers and agent state |
| `/ask <message>` | Ask the local model a player-facing question |
| `/model` | Show the configured model |
| `/model <name>` | Change the active model |
| `/model reasoning <effort>` | Set reasoning (`none`, `off`, `on`, `minimal`, `low`, `medium`, `high`, `xhigh`) |

## License

MIT
