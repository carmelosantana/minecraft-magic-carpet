# Magic Carpet

An enchanted rug for [Paper](https://papermc.io/) servers. Carry it in your off-hand,
jump, and it unfurls beneath you — then fly where you look until you sneak back down
to the ground.

Play it on **`play.xpfarm.org`** (Java Edition and Bedrock Edition via Geyser).

## How it works

1. Craft the rug, or receive one from `/carpet give`.
2. Hold it in your **off-hand**. On the ground it is an ordinary held item.
3. **Jump.** The carpet unfurls and flight begins.
4. Steer by looking — the carpet cruises forward, and looking up or down climbs or dives.
5. **Sneak** to lose altitude. Touching the ground stows the carpet back into your hand.

A charge drains while you are airborne and refills while you stand on the ground.
Run it dry in mid-air and the carpet vanishes — you will fall, and the landing counts.

## Commands

| Command | Permission | Description |
| --- | --- | --- |
| `/carpet give [player]` | `magiccarpet.admin` | Issue a rug |
| `/carpet off` | — | Dismiss your carpet in mid-air |
| `/carpet reload` | `magiccarpet.admin` | Reload configuration |

Alias: `/magiccarpet`.

## Permissions

| Node | Default | Gates |
| --- | --- | --- |
| `magiccarpet.use` | `true` | Deploying a carpet |
| `magiccarpet.craft` | `true` | Crafting the rug |
| `magiccarpet.admin` | `op` | `/carpet give`, `/carpet reload` |

## Java and Bedrock

Java players ride seated. Bedrock players fly standing by default, because of two
open Geyser bugs where a Bedrock rider's camera fails to update on a server-moved
mount. Both editions are configured symmetrically and either can be switched:

```yaml
flight:
  java-mode: seated       # seated | standing
  bedrock-mode: standing  # seated | standing
```

See [`docs/superpowers/specs/2026-07-21-magic-carpet-design.md`](docs/superpowers/specs/2026-07-21-magic-carpet-design.md)
for the full design and the upstream issues behind that default.

## Building

Requires JDK 25 and Maven.

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

The releasable JAR is written to `target/magic-carpet-<version>.jar`.

## Requirements

- Paper 26.1.2 build 74 or compatible
- Java 25
- Optional: WorldGuard (region restrictions), Floodgate (Bedrock edition detection)

Neither optional dependency is required. Without Floodgate, every player is treated
as Java Edition.

## License

[GNU Affero General Public License v3.0 or later](LICENSE). Copyright © 2026 Carmelo Santana.
