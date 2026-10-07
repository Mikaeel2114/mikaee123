# BedFight (Spigot 1.8.8, JDK 17)

Bed-destruction minigame with `minestormparty` party integration.

## Build
    mvn -B clean package        # -> target/BedFight-1.0.0.jar
Pushing to `main` builds the JAR via GitHub Actions (artifact `BedFight-jar`).

## Admin setup flow
    /bedfight setuparena <world>   load + teleport to the world folder
    /bedfight buildmode            toggle map protection bypass
    /bedfight po1 | po2            stand at the LOWEST point (void level) / HIGHEST point (build limit)
    /bedfight setspawn             game/spectator spawn
    /bedfight createteam <color>   register a team (becomes the selected team)
    /bedfight setteam [color]      block under your feet = team spawn
    /bedfight setbed [color]       look at the team's bed block
    /bedfight save                 writes plugins/BedFight/arenas/<world>.yml

## Permissions
`bedfight.play` (default true), `bedfight.admin` (op), `bedfight.rank.mvpplusplus` (private matches)

## minestormparty hook
`hook/PartyHook.java` uses reflection. If your API uses other method names, edit the
name tables at the top of that class.

## Extra admin tools
    /adminkiteditor          GUI: arrange the kit given at match start + respawn (saved to kit.yml on close)
    /adminkiteditor reset    back to the default MMC kit
    /beddefend po1|po2 [color]   corners of the region around a team's bed whose MAP blocks may be broken
    /beddefend clear [color]     remove that region
    /bedfight reload         reload config.yml, scoreboard.yml, kit.yml and arenas
    /leave  or  /l           leave the queue / forfeit the match

White wool, stained clay/glass and leather armor in the kit are re-colored to the team color.
Scoreboard layout (title, lines, ping, teams) is in plugins/BedFight/scoreboard.yml.
