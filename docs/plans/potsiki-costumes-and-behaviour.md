# Потсики: the costumes, the movements and the position

**Plan date:** 2026-10-25

Потсик is the PotSquad firmware's character, ported into the app. This plan
covers the second pass over that port: the costume list is cut down to the
eighteen the product wants, each of them is checked against the firmware
shape for shape, the character's movements are given the timing they need to
be watchable, and the place he stands in is remembered the way the settings
say it is.

The reference is the firmware itself — `firmwares/PotSquad/src/potsy.cpp`
(9 395 lines), with `src/shambler.inc`, `src/th3_0n3.inc` and
`Theme::drawWing()` from `src/theme.cpp`. Everything below is measured against
that file, not against the app's own current drawing.

## 1. The costume list

Eighteen costumes, with the names the product asked for. The firmware's twenty
lose `WOLF PELT` and `SHARK SUIT`; their drawing code goes with them.

| # | Enum | Name | What the firmware draws it as |
|---|------|------|------------------------------|
| 0 | `NONE` | Нет | The bare look: the tee, the shorts, the sneakers |
| 1 | `TANOOKI` | Тануки | The raccoon suit: the ringed tail, the pointed ears, the cream belly |
| 2 | `UNICORN` | Единорог | Baby blue over baby pink, the striped horn |
| 3 | `TINFOIL` | Фольга (Шапочка) | The crumpled foil hat, the little propeller, the glint |
| 4 | `SHADOW` | Тень (Ниндзя) | The masked face, the red headband with its travelling tails |
| 5 | `PLUMBER` | Сантехник (Марио) | The red cap, the moustache, the overalls, the white gloves |
| 6 | `TALLBRO` | Высокий (Луиджи) | The same, in green and taller |
| 7 | `SPACE` | Космос (Астронавт) | The bubble helmet, the suit, the drifting glint |
| 8 | `BLUEBLUR` | Синий вихрь (Ураган) | The blue fur, the quills, the white gloves, the red sneakers |
| 9 | `CAPTAIN` | Капитан | The tricorn with the gold braid and the skull |
| 10 | `CHROMEWING` | Хром крыло (Инсектоид) | The chrome wings, flapping behind him |
| 11 | `VOIDEYE` | Глаз пустоты | His head becomes a sphere of stars, with an iris that moves |
| 12 | `PARKA` | Снежная парка (Кенни) | The hood, the mittens, the swinging drawstrings |
| 13 | `YZZERD` | Йззерд (Волшебник) | The robe, the wide hat, the forked beard, the star |
| 14 | `OVER9000` | Свыше 9000 (Гермес) | The gi, the fringe, the scouter |
| 15 | `SHAMBLER` | Шамблер (Фрэнки) | Green-grey, the scar, the bandage on the forearm |
| 16 | `TH3_0N3` | Избранный | The coat, the three red stripes, the red glasses |
| 17 | `STRIPES` | Полосатый | The prison stripes, the cap, the iron cuff |

The names are stored per language, so the English build shows the same
eighteen in English and the Russian build shows the list above.

## 2. What has to change in the code

### 2.1 The costumes themselves

- `PotsikOutfit` loses `WOLFPELT` and `SHARK` and carries the new names.
- `wearsBaseClothes` loses `WOLFPELT`: the tee and the shorts stay on under
  `NONE`, `TINFOIL` and `CHROMEWING` only.
- `PotsikCostumes.kt` loses `drawWolfPelt()`, `drawShark()` and `peltOn()`.
- `PotsikBody.kt` loses the wolf cape, the pelt skulls, the shark hood and the
  shark fins, and every branch that mentions the two removed costumes.
- The screenshot test's outfit grid loses two rows.

### 2.2 Every costume, checked against the firmware

Each costume is checked for the same five things: the shapes it draws, where
they sit, how big they are, what colour they are, and what moves.

| Costume | To check |
|---------|---------|
| `NONE` | The tee, the shorts and the sneakers are the firmware's `outfitWearsBaseClothes` case: the tee at `hy+23..35`, the shorts at `hy+35..47`, the sneakers with their sole and lace |
| `TANOOKI` | The ears are behind the head, the belly patch is on the torso, the tail is behind the body and its wave is the firmware's 3.4 s S-wave with the double-flick |
| `UNICORN` | The horn is the firmware's cone with the six diagonal bands, tip at `hy-24` |
| `TINFOIL` | The hat is the firmware's five-facet cone with the crumple lines, the brim and the propeller that turns in 700 ms, plus the glint that cycles every 4.2 s |
| `SHADOW` | The lower-face mask, the headband with the knot, the two tails that flutter on a 900 ms wave, the sash and the throwing star |
| `PLUMBER` / `TALLBRO` | The cap dome and bill, the emblem, the moustache, the overalls with the bib, the straps and the buttons, the white gloves |
| `SPACE` | The helmet with its three rims, the drifting highlight on the 16 s arc, the glint riding it, the arm and leg spacesuits, the boots, the chest pack, the suit with the alternating console lights |
| `BLUEBLUR` | The five quills behind the head with the firmware's exact spike table, the white gloves, the red sneakers, and the dust puff while he walks |
| `CAPTAIN` | The coat with the shirt V and the lapels, the tricorn with its nine-point brim, the gold braid and the skull and crossbones |
| `CHROMEWING` | The wings behind him, the three-beat burst that decays, the shine that slides along the wing |
| `VOIDEYE` | The sphere with the rim, the starfield drifting through it, the iris that follows the gaze and holds, the lid |
| `PARKA` | The coat, the hood shell with the inner shadow, the hood repainted over his head with the face opening left clear, the mittens, the arms redrawn over the hood, the drawstrings that sway on a 9 s sine |
| `YZZERD` | The fur block in the robe's colours, the robe with the gold hem and the slippers, the sleeves with the gold cuffs, the collar, the epaulettes, the rune circles turning, the orbiting orb, the forked beard, the hat with its two gold bands and the pulsing star |
| `OVER9000` | The gi with the blue undershirt V and the belt knot, the eight hair spikes with the firmware's spike table and the sway, the glint running up one spike, the four fringe locks, the wristbands, the statics and the bolt to the floor |
| `SHAMBLER` | The jeans with the gap, the ragged points and the mud, the torn shirt with its notches and the hole, the scar, the cracked lens, the bandage whose loose end swings, the one sneaker, the three flies |
| `TH3_0N3` | The coat with the stirring hem, the collar, the round shades with the falling code, the glitch, the bullet time, the cat, the spoon |
| `STRIPES` | The cap, the stripes painted over the fur so they follow the pose, the 1337 patch, the iron cuff |

### 2.3 The closed eyes

The firmware draws the sleeping lids in `furLight`, which on the default
yellow fur is the same colour as the face — so the eyes vanish entirely when
he is asleep or waking up. In the app the lids are drawn in a dark line
instead, so a shut eye still reads as a shut eye on the face.

### 2.4 The working tile

`ToolMessage.kt` draws the working tile with a `surfaceVariant` background,
which puts a rectangle behind the character while a request runs. The tile
keeps its place — it is the character's slot, and the caption under it is
measured against it — but the rectangle goes: the background becomes
transparent, so the character stands on the chat rather than on a card.

The same tile is used by the main chat and by the interactive (pots-ui) chat,
so both lose the rectangle at once.

### 2.5 The timings of the animations played during a request

The playlist that plays while a request runs gives each animation a slot, and
the animation has to fill the slot: it must not be cut off half way through,
and it must not hold for so long that it becomes a still picture.

- One slot is **6 seconds** at the normal tempo.
- The tempo still scales it, but the slot never falls below **4 seconds** and
  never rises above **12 seconds**.
- The mood and the pose both last the whole slot. Today they last the
  unscaled 3.2 seconds while the slot is the scaled one, so at the default
  70 % tempo every animation is cut a third of the way through.
- The tool's own pose, the one that says *what* the agent is doing, holds for
  at least 4 seconds before the playlist takes over.

### 2.6 Where he stands

| Problem | Fix |
|---------|-----|
| The position is never written to disk | `pinnedX`/`pinnedY` get their own settings keys |
| `sizePercent`, `tempoPercent`, the banter switch, the drag switch and the placement mode are all dropped on every save | Each of them gets its own key |
| The size slider works backwards — raising it makes him smaller | One size model: the drawn size and the bounds are computed from the same number |
| `sizePercent` is not in the bounds, so a 150 % Потсик hangs off the screen | The bounds use the same effective size the drawing uses |
| The default placement mode contradicts its own documentation | Session is the default, as the documentation says |
| A move in the session mode is written to disk, so "session" is really "for ever" | Only the persistent mode writes the position down; the session mode keeps it in memory |
| The agent's `move` reports success in the system mode, where nothing moves | The tool says the move was refused |
| Switching to the persistent mode loses the spot he is standing in | The mode switch seeds the stored position from where he is |

### 2.7 Where he is shown

| Section | Now | After |
|---------|-----|-------|
| Chat, empty screen | the character | the character |
| Chat, a request running | the character | the character, on no tile |
| Chat, under the last answer | the character | the character |
| Interactive (pots-ui) chat | the character | the character, on no tile |
| Mini-app runner | nothing | the character |
| Pots Build | nothing | the character |
| Sandbox / terminal view | nothing | the character |

One copy of him on screen at a time: the overlay draws him, the anchors only
say where he belongs.

## 3. The order of the work

1. The costumes: the enum, the names, the dead code.
2. The closed eyes.
3. The tile.
4. The timings.
5. The position and the size.
6. The sections.
7. The costume-by-costume pass.
8. The tests, the screenshots, the documentation.
9. The build, the emulator, the dashboard.

## 4. What "done" means

- The eighteen costumes are the only ones in the list, with the names above.
- Each of them is drawn the way the firmware draws it, with its own
  animations running.
- A shut eye is still a shut eye, not a blank face.
- There is no rectangle behind the character while a request runs.
- Every animation of the working playlist plays its whole cycle.
- He can be dragged, moved by the agent, and his place is remembered as the
  placement mode says.
- He is on screen in every section.
- The unit tests pass, the format is clean, the APK builds, every costume is
  checked in the emulator, and the APK is on the dashboard.
