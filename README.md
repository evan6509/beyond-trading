# Beyond Trading

NPCs that feel alive — with personalities, memories, conversations, and local LLM-powered decision-making, all inside Minecraft 26.2.

## What it does

- Custom AI NPCs that remember interactions and act on them
- Right-click to talk; NPC responds via a local LLM (vLLM or LM Studio)
- NPC chooses actions (go home, farm, follow, warn, trade, etc.) based on personality and situation
- Memories persist per world as Markdown files
- No cloud APIs — everything runs on your machine

## Quick start

1. Start your LLM server (vLLM or LM Studio)
2. `./gradlew runClient`
3. `/beyondtrading summon` — spawn Thomas
4. Right-click Thomas to talk

## Requirements

- Minecraft 26.2 (Java Edition)
- Fabric Loader 0.19.3+
- Java 25
- A local LLM server at `localhost:8000` (vLLM) or `localhost:1234` (LM Studio)

## Commands

| Command | Description |
|---------|-------------|
| `/beyondtrading summon [<pos>]` | Spawn an AI NPC |
| `/beyondtrading inspect <entity>` | Show NPC personality, goals, memories |
| `/beyondtrading config` | Show current LLM configuration |

## License

MIT
