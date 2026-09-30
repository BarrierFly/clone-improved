# Clone Improved

[English](README.md) | [中文](README_zh-CN.md)

A server-side Fabric mod that supercharges the vanilla `/clone` command with composable
move/rotate/mirror transforms, air masks and undo/redo. Vanilla clients work without
installing anything.

## Requirements

- Minecraft **1.19.4**, **1.21.10**, **1.21.11** or **26.2** (see the release list for per-version jars)
- Fabric Loader + Fabric API
- Permission level 2 (OP) — inherited from the vanilla `/clone` node
- Server-side only: the mod must be on the *server* (or single-player); players need nothing

## Commands

Everything below is an extension of the vanilla `/clone`; all vanilla syntax
(`replace|masked|filtered <filter>`, `force|move|normal`, `from <dim>`, `to <dim>`,
`strict` on 1.21.2+) keeps working unchanged.

### Transform chains: `move` / `rotate` / `mirror`

```
/clone <begin> <end> <destination> <chain> [force]
/clone from <sourceDim> <begin> <end> [to <targetDim>] <destination> <chain> [force]

chain ::= [mask_begin|mask_end|mask_both] transform+
transform ::= move | rotate (cw|ccw|reverse) | mirror (x|z) <coordinate>
```

- `move` **must** appear exactly once; `rotate` and `mirror` at most once each; they apply in
  the order written. `rotate cw` = 90° clockwise seen from above (north → east), `ccw` =
  counter-clockwise, `reverse` = 180°.
- `move` and `rotate` pivot around the region's current lower-NW corner; `mirror x 1.0`
  reflects through the vertical plane `x = 1.0` (world coordinate, `0.5` steps). Vertical
  rotations/mirrors are intentionally not supported.
- Direction-sensitive blocks (stairs, rails, doors, logs,…) are rotated/mirrored correctly.
- The vanilla path also composes: `/clone <begin> <end> <dest> masked move rotate cw`.
- `force` may trail any chain end — including the vanilla-mode `move`, so
  `/clone <begin> <end> <dest> replace move force` (move + allow overlap) is accepted even
  though vanilla cannot combine the two.

Examples:

```
/clone 0 64 0 10 70 10 100 64 100 move                  # vanilla-equivalent "replace move"
/clone 0 64 0 10 70 10 100 64 100 move rotate cw        # move, then rotate 90° clockwise
/clone 0 64 0 10 70 10 100 64 100 mirror z 105.5 move   # mirror content, then move
/clone 0 64 0 10 70 10 100 64 100 mask_begin move reverse force
/clone 0 64 0 10 70 10 100 64 100 mask_end rotate ccw move
```

### Air masks: `mask_begin` / `mask_end` / `mask_both`

These replace vanilla `masked` (which is kept untouched) and can be used alone or before a
transform chain:

| Mode | Copies | Destination check | Failure |
|---|---|---|---|
| `mask_begin` | only non-air source blocks (≡ vanilla `masked`) | none | 0 blocks copied → `clone.failed` |
| `mask_end` | everything, including air | every destination position must be air | first non-air position → command fails with **zero** writes |
| `mask_both` | only non-air source blocks | positions that will receive a non-air block must be air | same as above |

Masks never change what `move` clears from the source region (vanilla parity).
Structure void is not air, so `mask_begin` copies it — same as vanilla `masked`.

### Undo / redo

```
/clone undo [<player>] [confirm|cancel]
/clone redo [<player>] [confirm|cancel]
```

Every successful `/clone` records its command line, time, executor and complete before/after
snapshots of every affected region — the destination region, the source region for `move`,
including masked-out air.

- `/clone undo` proposes undoing your last clone and shows the command, time, regions and
  affected-block count. Finish it with `confirm` or `cancel`.
- If the region changed since, the proposal warns how many blocks were modified — confirming
  overwrites them.
- `/clone undo <player>` proposes undoing *another* player's last clone; if they are online
  they are told who proposed it. Either the proposer or the owner can `confirm`/`cancel`.
  The target does not need to be online.
- After undoing you can keep undoing older records; `/clone redo` reverses the last undo with
  the same proposal flow. A new `/clone` clears your redo stack.
- Only one proposal can be pending at a time; records are kept until the server stops.
- Undo/redo refuses to run while a recorded region spans unloaded chunks (nothing is modified
  in that case) — move closer to the area, or load it, and try again.
- A clone that fails with `clone.failed` but still changed the world (e.g. a `move` whose
  source was already cleared) keeps its undo record, so even that failure can be reverted.

## Compatibility

- **Reden**: no command, mixin or network conflicts. Both mods record `/clone` writes — use
  one undo system per clone, not both.
- **Undo Mod** (client keybinds): your clones are not player interactions, so they never
  enter its history; its edits inside your regions show up in the modified-block warning.
- **gitmatica**: client-side only, no interaction.

## Configuration

`config/clone-improved.json`:

| Key | Default | Meaning |
|---|---|---|
| `maxRecordsPerPlayer` | 32 | undo records kept per player (oldest evicted) |
| `maxTotalMemoryMiB` | 64 | global soft memory budget for snapshots |

## Known limitations

- Pending block ticks (scheduled water flow etc.) are copied for plain-translation clones
  like vanilla, but not for rotated/mirrored ones; they are never recorded for undo.
- Entities are not cloned or moved (vanilla parity) and not restored by undo.
- On 1.19.4 the client language is not synced to the server, so undo/redo messages are
  English there; 1.21.10+ picks English/Chinese automatically.

## Building

Requires JDK 17+ to *run* Gradle (toolchains fetch 17/21/25 as needed):

```
gradle build          # builds every version node
gradle :buildAndCollect
```

Multi-version support uses [Stonecutter](https://stonecutter.kikugie.dev/); the shared source
lives in `src/main/java` and per-version divergences are isolated in
`multiver/MultiversionHelpers`.

## License

[WTFPL v2](LICENSE) — do what the fuck you want to.
