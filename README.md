# Minecraft Mod - Mars Command

The Minecraft mod for Mars Command. The Moon transmits orders through lightning.
Players complete persistent supply-delivery and location missions, earn ranks,
and contribute to a hidden, shared server objective.

## Supported versions

| Component | Version |
| --- | --- |
| Minecraft | 1.21.1 (exact) |
| NeoForge | 21.1.250 (exact) |
| Java | 21 |
| Mod | 0.3.0 |

The same JAR supports the client and dedicated server. It has no client-only
classes in its dedicated-server path or additional mod dependencies. Compatibility with the full Mars
modpack still needs an in-game check; matching loader versions alone cannot
certify interactions with other mods.

## Playing

Healthy, well-fed Survival/Adventure players are considered automatically.
Ordinary assignments require sufficient mission-specific readiness, required
equipment, no existing assignment, and an expired cooldown. This is a
deterministic readiness assessment, not a measured probability of success.
There is no night-time restriction. Operators can define missions in any
registered dimension; normal assignments require a target in the player's
current dimension.

An ordinary transmission removes **one heart**, independently of armour and
absorption. Emergency transmissions remove **half a heart**, without reducing
health below half a heart. Already weaker players are neither damaged further
nor healed. Lightning is rendered only on the recipient's client: it does not
cause fires, hit nearby entities, transform mobs, clean copper, or activate
lightning rods.

### Command GUI

Open your inventory and click the **MC** button beside its lower-right corner.
This opens a private, non-pausing mission tree with **Deliveries** and
**Surveys** branches. Only objective types with issued records appear.
Scroll the tree to see older records, collapse/expand branches, and select a
mission to read its details. Long briefings can be scrolled independently.

The tree contains **your ongoing and completed missions only**. It never
includes future assignments, another player's records, hidden prerequisites,
or the Grand Plan. Expired, refused, pending-replacement, and transferred-away
assignments are not tree nodes. Completed missions survive restarts and remain
available through paginated synchronization; there is no fixed visible-history
cutoff. Emergency briefings remain abbreviated even after completion.

The GUI displays score/rank, progress, coordinates, and remaining active time.
**Submit Supplies** uses the existing destination and inventory checks.
**Refuse** first shows the heavy penalty; **Confirm Refusal** only works within
the server's confirmation window. Completed or expired work cannot be submitted
again, and the server validates every action rather than trusting the GUI.

New assignments are typed onto a non-blocking **incoming transmission overlay**,
so they never force a screen open or stop movement. Multiple messages are
queued in arrival order. Inside the command screen, the overlay is compact to
keep tree nodes and controls accessible. Completion, handover, submission,
refusal, and GUI errors also appear in the interface, not normal mission chat.
Vanilla advancement/death messages and operator command output are unchanged.

The inventory button is disabled while an item stack is held on the cursor,
avoiding an unintended inventory close with a carried stack. Creative inventory
screens also have the button. Disconnecting clears cached records, confirmations,
and transmissions so one server's missions cannot appear on another server.
The screen refreshes automatically and has a manual **Refresh** button; timeouts
are reported explicitly.

Both client and server must use the new mod version: the GUI uses networking
protocol 2. Existing version-0.2.0 saved mission data needs no migration.

The following commands remain available as shortcuts:

| Command | Purpose |
| --- | --- |
| `/marscommand missions` | Open the command GUI. |
| `/marscommand mission <id>` | Open one of your ongoing/completed mission records. |
| `/marscommand submit <id>` | Deliver matching supplies while at the destination. |
| `/marscommand status` | Open the command GUI with your score and rank. |
| `/marscommand refuse <id>` | Request refusal and see the heavy score penalty. |
| `/marscommand refuse <id> confirm` | Confirm refusal before the confirmation window expires. |

Supply turn-ins consume only the quantity still required, from your main
inventory and offhand, not worn armour. Partial turn-ins are supported. Location
missions complete when you reach the destination radius in the correct
dimension. Mission IDs distinguish concurrent emergency assignments.

### Deadlines and emergency handovers

Deadlines count **server-running elapsed time**, even when a player logs out,
but pause while the server is shut down. Changing world time does not affect
them. They are not extended by reconnecting, refusing, or transferring a mission.

During the final **10%** of the original mission duration, progress **strictly
below 5%** triggers emergency reassignment. The next player is chosen globally
by estimated distance to the objective plus configured dimension-crossing cost,
even when weak, poorly equipped, or already busy. Spectators, Creative players,
fake players, and dead entities are not assignment candidates.

An emergency recipient sees only the simple objective, dimension, coordinates,
progress, and remaining time. The original briefing is not disclosed. Each
replacement gets a configurable attempt window before another handover, but
the original deadline takes precedence. Previous owners cannot receive that
same mission again. If no replacement exists, the obligation stays pending
until someone is available or it expires.

Completion increases score. Refusal receives the heaviest penalty and transfers
the obligation rather than cancelling it. Automatic-handover and expiry
penalties depend on that owner's verified progress contribution; neither
penalty is repeatedly charged for the same transition. For handovers, progress
contribution is normalized against the 5% cutoff, so even a small attempt can
reduce the penalty. Rank affects normal assignment frequency, maximum template
difficulty, supply quantity, and deadline duration.

## Server administration

On the first world startup, the mod creates
`<world>\serverconfig\mars-command.json`. This **server-local** JSON contains
policies, rank bands, mission templates, and the private goal catalogue; it is
not registered as a synchronized NeoForge config. Edit it while the server is
stopped, then restart. Invalid configuration or corrupt saved state disables
the director with an explicit server-log error instead of resetting progress.
Restore/fix the affected file before restarting; do not delete world data to
hide an error.

Default policies:

- Scheduler: every 2 active seconds; replacement attempt window: 15 seconds.
- Readiness: at least 75% health, food level 14, required equipment, and a
  weighted score of at least 0.85.
- Completion: +10; refusal: -30; handover: up to -5; expiry: up to -10.
- Ranks: Initiate below 0, Operative from 0, Pathfinder from 50, Commander
  from 150. Higher ranks receive more frequent/harder assignments and shorter
  durations, bounded by configured duration/quantity limits.
- Cross-dimension cost: 4096, plus geometric distance after dimension scaling.
  The Nether uses scale 8; other dimensions default to scale 1 unless explicitly
  listed in `dimensionScales`. This heuristic does not implement a transport
  route or guarantee that a forced cross-dimension mission is reachable.

The initial example catalogue has an iron delivery at world spawn and a survey
point 96 blocks away. Check the terrain and adjust **all target coordinates**
before real play: the example uses spawn height and does not excavate a route,
find safe ground at each target, or generate/load remote chunks. Custom dimensions
need their own templates. Configure equipment prerequisites using item IDs or
`#namespace:tag` references.

One valid goal is selected deterministically from the world seed and persisted.
The example goals require five successful missions; increase their requirement
counts to support a longer campaign. When the goal is fulfilled, new
assignments stop. There is no automatic reset/new campaign command in this
milestone. Existing assignments keep their snapshotted targets and durations
when configuration changes; do not remove their template/goal IDs or tighten
validation bounds beneath existing saved values.

| Operator command (permission level 2) | Purpose |
| --- | --- |
| `/marscommand admin inspect` | Inspect the private selected goal, progress, and active clock. |
| `/marscommand admin validate` | Validate the currently loaded policies and state. |
| `/marscommand admin readiness <player>` | Show mission-specific readiness diagnostics. |
| `/marscommand admin assign <player> <template>` | Assign supported unfulfilled work; normal readiness checks still apply. |
| `/marscommand admin strike <player> <emergency>` | Test ordinary (`false`) or emergency (`true`) transmission safety. |

The chosen goal, full catalogue, ownership records, clock, and player statistics
are saved in `<world>\data\mars_command.dat`. Back up that file with the world
and its server-local JSON. The mod uses Minecraft's normal save lifecycle:
an abrupt process crash can roll back to the last world save. Clean shutdowns
and restarts preserve missions, scores, original deadlines, and ownership.
Do not edit saved JSON/NBT by hand while the server is running.

Player-facing messages never disclose the selected goal or describe the Moon's
implementation. This is narrative secrecy, not protection against an operator,
integrated-world owner, source/JAR inspection, or players deducing the story.

### Optional integrations

The client pack manifest lists **Millenaire 9.0.2**, Create, and KubeJS.
These are not required dependencies and no civilisation adapter is implemented.
The manifest's client entries alone do not certify the live server's mod set.

A [public NeoForge 1.21.1 Millenaire fork](https://github.com/Leviaria/Millenaire)
documents villages, construction, trade, and reputation, but its published
source metadata identifies `9.0.0-dev-preview.7`, not the pack's `9.0.2`.
The pack entry has no source-page provenance, and no matching JAR was available
locally. Verify the exact artifact/source and integration API before adding
civilisation objectives; no guessed API, reflection shim, or hard dependency
is included.

## Development

Run commands from this directory. Use a 64-bit JDK; Java 21 is recommended.
The Gradle wrapper is included, so a separate Gradle installation is not needed.
Gradle uses a Java 21 toolchain and can automatically download it if it is
missing. The first build also downloads Minecraft and NeoForge development
dependencies, so it needs internet access and can take several minutes.

### Build

```powershell
.\gradlew.bat build
```

The distributable is `build\libs\mars-command-mod-0.3.0.jar`. Copy this JAR into
the `mods` directory of a Minecraft 1.21.1 / NeoForge 21.1.250 installation while
Minecraft is closed. For the managed Mars client pack, include the JAR in the
signed pack release rather than placing an unlisted file in the managed
installation.

On Linux or macOS, invoke the wrapper with `sh gradlew build`.

### Launch a development client

```powershell
.\gradlew.bat runClient
```

This uses an isolated `run\client` game directory. At the title screen, open
**Mods** and confirm that **Mars Command 0.3.0** appears. Its
`run\client\logs\latest.log` should contain:

```text
Loading Mars Command 0.3.0
Mars Command initialized (mod id: mars_command)
```

Development clients do not provide normal Microsoft account authentication.
Use your regular authenticated launcher installation to check joining the live
Mars server; do not disable authentication on the live server.

### Launch a development server

```powershell
.\gradlew.bat runServer
```

This uses `run\server` and never modifies the live Mars server. The development
environment starts directly without the normal EULA prompt. An installed
dedicated server still requires you to read the linked Minecraft EULA and, if
you agree, set `eula=true` in its `eula.txt` before starting it. The development
server log should contain the same initialization messages and eventually
`Done (...)`. Enter `stop` in its console to shut down cleanly.

The local server uses port 25565 by default. Change `server-port` in
`run\server\server.properties` if that port is already occupied. Keep
`online-mode=true` for authenticated clients. Only use `online-mode=false` for
isolated local development, never on the live server.

### Tests

```powershell
.\gradlew.bat test runGameTestServer
```

JUnit covers mission rules, scoring, thresholds, repeated handovers, active
time, and saved-state roundtrips. Minecraft GameTests use isolated local worlds
and mock connected players to exercise supply consumption, refusal, concurrent
emergency assignments, abbreviated-message privacy, and lightning safety.
GUI tests cover personal-record filtering, no future placeholders, bounded
pagination, packet roundtrips, typewriter Unicode/queue behavior, and
server-validated GUI actions.
GameTest classes/structures are development-only and are not in the
distributable JAR. A full-modpack authenticated play test is still needed
before deploying a signed pack update.

For an automated visual client check, first run the GameTests, then copy their
`run\gametest\world` into a **new** `run\client\saves\MarsCommandGuiTest`
directory. Never use an existing player world. Run:

```powershell
.\gradlew.bat runClientGuiTest
```

This development-only fixture verifies the inventory button (including held-stack
safety), private completed/ongoing tree, typewriter overlay, and real GUI supply
submission. It saves `mars-command-*.png` images in `run\client\screenshots`
and exits. Check the log for `MARS_COMMAND_GUI_SMOKE_PASSED`; a successful Gradle
exit alone does not certify the visual assertions. Remove only that named test
world after use, not your other saves.

## Future roadmap

These features are planned, not implemented in this milestone:

- Real model-backed Moon planning behind validated server-side mission rules
- Player-built space elevators and interstellar destinations
- Verified civilisation progression objectives, including Millenaire
- Star mapping capabilities
- Orbital mechanics simulation
- Variable terrain generation based on planet characteristics and type
  - Along with realistic resource distribution and ecosystem simulation
- Local solar system simulation with accurate planetary orbits and interactions
  - Including gravitational effects and potential collisions
- Integration with Minecraft's existing mechanics and modding API
