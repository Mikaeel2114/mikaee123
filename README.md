# BedFight 1.1.0 (Spigot 1.8.8, Java 17)

Build: `mvn clean package` -> `target/BedFight-1.1.0.jar` (needs internet for the Spigot API dependency).

## Arena setup (map protection)
1. `/bedfight setuparena <world>` - pick the template world (build it with the arena, beds included).
2. `/bedfight po1` and `/bedfight po2` - mark the two corners of the arena box (the box also defines the build-height limit).
3. `/bedfight createteam <color>`, then `/bedfight setteam` (stand on the spawn block) and `/bedfight setbed` (look at the bed).
4. Build the whole map (floor, islands, bridges, bed covers) in the template world while in `/bedfight buildmode`; template worlds are protected otherwise.
5. `/bedfight save`.
6. Matches run in a copy of the template. Everything that exists in the template is permanent map: it cannot be broken or blown up. Only blocks placed by players during the match can be broken, so bed defenses stay breakable while the floor/structures never get destroyed.
7. `/beddefend` was removed - no region setup is needed any more.

## Config
`settings.yml` (respawn delay, countdown, sounds, messages, spectator options, private-match options), `config.yml`, `scoreboard.yml`. Missing keys are added automatically on update and existing values are never overwritten. `/bedfight reload` reloads them.
