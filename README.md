# BedWars1058-ArenaJoinMessage

Hypixel-style "join this game" notification for [BedWars1058](https://gitlab.com/andrei1058/BedWars1058).

When a player joins an arena, everyone else gets a chat message such as

> **BED WARS » [VIP] Steve joined Doubles on Garden (3/16) CLICK TO JOIN!**

Hovering shows the details, clicking joins the arena. Everything is configurable in `config.yml`
and in game; the plugin can be reloaded without a restart.

* Written in Kotlin 2.2.21, built with Gradle (Shadow plugin), Kotlin runtime bundled and relocated
* Java 8 bytecode, so it runs on Spigot/Paper servers from **1.8 up to 26.x** (no NMS, no version-specific code)
* Needs BedWars1058 (shared-server / multi-arena mode, not BungeeCord scalable mode)

## Building

```
./gradlew build
```

The server jar is `build/libs/BedWars1058-ArenaJoinMessage-<version>.jar` (the `-plain` jar has no Kotlin runtime, do not use it).

Settings live in `gradle.properties` (`pluginVersion`, `bedwarsApiVersion`, `spigotApiVersion`).
To build against a local BedWars1058 API jar: `./gradlew build -PbedwarsApiJar=/path/to/bedwars-api.jar`.

Any JDK 8+ can build it. The plugin is compiled against the 1.8.8 Spigot API on purpose, so it cannot accidentally use
something that does not exist on old servers. Because the `bungeecord-chat:1.8-SNAPSHOT` that spigot-api 1.8.8 asks for is
no longer hosted anywhere, `build.gradle.kts` swaps in the `1.16-R0.4` chat library for compiling only
(the plugin uses nothing but `TextComponent`, `ClickEvent` and `HoverEvent`, which all versions have).

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| `/ajm toggle` | `arenajoinmessage.toggle` (everyone) | Turn the notifications on/off for yourself |
| `/ajm join <arena>` | `arenajoinmessage.join` (everyone) | What the chat link runs; checks the arena, then runs `join.command` |
| `/ajm reload` | `arenajoinmessage.admin` (op) | Reload `config.yml` |
| `/ajm preview` | `arenajoinmessage.admin` (op) | Show the notification to yourself |
| `/ajm get <path>` | `arenajoinmessage.admin` (op) | Show a config value |
| `/ajm set <path> <value>` | `arenajoinmessage.admin` (op) | Change a config value in game (TAB completes paths), saved to `config.yml` **with comments kept**, then reloaded |

Aliases: `/arenajoinmessage`, `/arenajoin`. Players need `arenajoinmessage.receive` (default: everyone) to get the messages.

`/ajm set` changes single values (true/false, numbers, text, new `gamemodes.*` names). Lists such as
`trigger.states` are edited in the file followed by `/ajm reload`.

## Configuration highlights

* `message.lines` / `message.hover` / `message.click` - the text, hover text and click action (`RUN_COMMAND` or `SUGGEST_COMMAND`).
  Placeholders: `{player} {displayname} {map} {arena} {group} {gamemode} {players} {max} {status}`.
  Colors: `&` codes and `&#RRGGBB` hex (hex only on 1.16+).
* `gamemodes` - names by team layout: `"<teams>x<perTeam>"` first, then `"<perTeam>"`, then `fallback` (e.g. `4v4v4v4`).
* `trigger` - which arena states are announced, per-arena cooldown, hide full arenas, minimum players, blacklists.
* `recipients` - skip players already in a game (default), limit to certain worlds, allow `/ajm toggle`.
* `join.command` - the command the click finally runs as the player (default `bw join {arena}`), so parties and VIP rules behave like the normal command.
* `messages` - every text the plugin itself sends.

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.