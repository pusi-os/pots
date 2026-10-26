# Потсики (Potsiki)

**Last verified:** 2026-10-25

Потсик is the animated character that keeps you company in the chat. He is
drawn entirely in code — no images, no sprite sheets, no animation files — so he
scales to any size and costs nothing to ship.

He **is** the PotSquad firmware's Потсик. The body, the costumes, the moods,
the emote poses, the bubble and the chatter are the firmware's own, ported
shape for shape: the same rounded-box skull and cowlick, the same arm that is
one line from the shoulder to the hand, the same blink schedule, the same
eighteen costumes, the same twenty-eight-second idle roll, and the same lines
of chatter in English and Russian, side by side.

The PixelTag app's copy of him — Squachy, as the firmware used to call him —
was changed into Pots at the same time, so the two builds agree and either one
can be used as the reference.

The port keeps the firmware's coordinate system: the character is 56 units
tall, the origin is the top of his head, and every shape is placed in those
units and scaled by one number.

## Where he appears

There is exactly **one** copy of him on screen at a time. He is drawn by a
single overlay over the whole app; the places in the layout below are
*anchors* — they only say where he belongs, and the highest-priority one on
screen wins. A section that has no anchor of its own puts him in the corner,
out of the way of what it is showing.

| Place | What he does |
|-------|--------------|
| Chat, empty screen | Stands where the old logo and "Добро пожаловать в Потс" used to be, playing his idle loop |
| Chat, a request running | Stands in the working band, in a fixed strip under the messages, playing the animation that matches the tool currently running. The strip does not scroll with the messages, so he is in it on the first request and on the tenth |
| Chat, under an answer | Sits to the right of the buttons under the answer, a little way in from the edge. While the reply is being read out he talks — the mouth flaps and one hand gestures |
| Interactive (pots-ui) mode | Stands above the mode's own title, and in the working band while the request runs |
| Mini-app runner | Stands in the corner of the app he is not part of |
| Pots Build | Stands in the corner of the build screen |
| Terminal / sandbox view | Stands in the corner of the terminal |

The working band wins over the footer: he leaves the footer when the next
request starts and comes back when it is done.

## The working band

While a request is running, a band sits under the messages: the character in
it and the state under him. The band's background is transparent — **there is
no tile behind him at all**, neither a card nor a rectangle: he stands on the
chat, and the band around him is nothing but air. The band does not scroll
with the messages.

The state — the tool that is running — is a caption **under** the character,
not beside him: the picture is who is working, the label is what for.

The caption's place is measured out whether there is a caption or not: the
line fades in and out over the space it always has. A caption that pushed the
layout around would take the character with it, and he would jump out of his
place every time the agent started or finished a tool.

## What he does

The chat tells the engine what it is doing, and the engine decides how he
shows it — the same way the firmware's screens tell him what is happening
around him.

| The app is | He is | He says |
|------------|-------|---------|
| Idle | Whatever his own roll decides — a walk, a stretch, a dance, a nap, or nothing at all | A line from his idle pool |
| A tool is running | The pose that matches the tool: the binoculars for a search, the phone for a message, the calendar for a task, the notebook for a memory — and then a list of animations played through until the request is over | "Ищу." / "Looking it up." |
| The reply is being read out | The talking pose, his mouth flapping every 160 ms | "Вот, слушай." / "Here it is." |
| Something went wrong | The double-take: he sees it, flinches and settles | "Что-то пошло не так." / "Something went sideways." |

The moods are the firmware's, and each one is a rule the renderer follows
rather than a clip of frames: `BOUNCE` is a hop with the squash at the bottom
of it, `WALK` is a leg cycle with the arms swinging against it, `SHOCKED` is
a double-take that recoils and settles, `SLEEPY` is the slow breathing with
the eyes closed and the "Z" drifting up.

A shut eye stays a shut eye: the lids are drawn as a dark line, in the same
keyline every other outline of his is drawn in. The firmware draws them in
its own light fur, which on the default yellow is the very colour of the
face, so a sleeping Потсик has no eyes at all there.

The emote poses are the firmware's too — the salute, the bow, the hug, the
crouch, the howl, the selfie — and the agent's tool can ask for any of them.

### The list of animations a request plays through

When a request starts, a list of **twenty** animations is made at random out
of everything he knows how to do, and they are played one after another until
the request is over: a dance, a stretch, the binoculars, a wave, the shuffle.
Every other one of them goes with a line, so a long request is a conversation
rather than a silence with a character in it.

The list is made from the animation catalogue the app already has — nothing
here asks a model for an opinion, so a long request costs no tokens. The
firmware rolls its dice the same way, a few times a minute, and picks
something to do from the same pool; here the roll happens once per request
and the list is played to the end, over and over.

### How long an animation is given

An animation has to finish its own cycle before the next one starts: a dance
that is cut in half reads as a glitch, not as a dance.

- One slot is **six seconds** at the normal tempo.
- The tempo scales it, but the slot never falls below **four seconds** and
  never rises above **twelve**. A slot under four seconds makes the character
  look like it is twitching; a slot over twelve makes the request look like
  it has stopped.
- The mood and the pose last the whole slot, so an animation always fills
  the time it is given.
- The tool's own pose — the one that says *what* the agent is doing — is
  given the same floor of four seconds before the playlist takes over.

## The bubble

When he has something to say, it comes out in a bubble above his head: a
round-cornered rectangle in the firmware's pink outline, with a small tail
pointing down at him. It pops in over a fifth of a second, overshooting
slightly on the way, the way the firmware's does.

The text is drawn by Compose rather than by the firmware's bitmap font,
because that font has no Cyrillic in it and the app has to say the same
things in Russian.

## The chatter

His lines are the firmware's own, with the English and the Russian in the
same slot — the Russian line in a slot is the translation of the English line
next to it, so a board that picks a slot at random picks the same moment in
either language.

The pools: the idle lines, the boredom, the encouragement, the noir hours,
the sleepy, the stretch, the gum, the lean, the trip, the dance, the walk,
the wink, the watching, the pet, the held, the petting, the head, the belly
and the feet, the flick, the duck, the shake, the backing away, the sit, the
landing, the dizzy and the thrown.

The firmware's lines about radio tags and catching things do not mean
anything in a chat app, so those pools were left behind; the rest are here.

## Touching him

He reacts to being touched, the way the firmware's does.

| You | He |
|-----|-----|
| Tap him | He bounces and says something. Where you tapped decides what: his head gets a pat, his belly gets a giggle, his feet get a hop |
| Hold a finger on him | He bounces for longer and says something else |
| Stroke him | He bounces and says something, every couple of seconds, for as long as the stroke lasts |
| Drag him | He comes with the finger, hanging from it with his legs kicking and his arms windmilling. A finger that was still moving when it let go **throws** him: he flies, bounces off the floor, and sits down dizzy if it was a hard landing. A finger that had already stopped **drops** him, and he lands with a squash |
| Swipe across him | He slides into the wall, wobbles, and comes back |

The gestures are the firmware's own, with one difference: on the device he is
picked up after a hold, here the drag picks him up at once, because a chat is
not a device with a button bar and the character should follow the finger
from the first movement. Everything else — the tap, the hold, the stroke, the
flick, the throw, the drop — is the firmware's rule, and the throw is the
firmware's physics: gravity, a bounce off the walls, a weaker bounce off the
floor each time, and then he sits down.

He can be picked up at any time: on the empty screen, in the working band
while a request runs, and in the footer under the last answer.

## Moving and resizing him

He can be dragged anywhere over the screen and pinched bigger or smaller with
two fingers — unless the placement mode is System, which says the chat
decides where he stands and switches the drag off. The stage keeps him inside
the screen either way — the whole of him, feet included, has to stay visible,
and the size is capped by the screen so he can never grow into a shape too
big to fit.

There is **one** size model. The size he is drawn at is the size the layout
gives him, times the layout's own factor for that place, times the user's
size settings, times the pinch. The drawing, the box the finger grabs and the
bounds that keep him on the screen all work from that one number, so they
cannot disagree about how big he is. Before, the drawing and the bounds each
had their own, and a big Потсик could hang off the screen.

The bounds live in one place, `clampPlacement()`, because three different
callers have to obey the same rule: the drag, the agent's tool and the
layout's own defaults.

## The agent's tool

**Settings → Tools** has a switch for **Потсик**; the on-device model can be
given the same tool in **Settings → Локальная модель**. It is off by default.

The tool is `potsik`, with an `action` switch:

| Action | Arguments | What it does |
|--------|-----------|--------------|
| `list_animations` | — | Lists every animation with its id and a sentence describing it |
| `play_animation` | `animation` | Makes him play that animation for ten seconds, overriding whatever the phase would pick |
| `move` | `x`, `y` | Puts him at that spot, as fractions of the screen (0 = left/top edge, 1 = right/bottom edge) |
| `set_scale` | `scale` | Makes him that size, relative to his normal one |

A position that would take him off the screen is pulled back into it, and the
tool says so. In the System placement mode a move is refused outright, and
the tool says that too rather than reporting a success that did not happen.
The animation ids are the enum names in lower case
(`idle_wave`, `work_search`, `speak_talk`, `error_shake`, `emote_howl`); they
are matched loosely, so `IDLE-WAVE` and `idle wave` work too.

## The settings section

**Settings → Потсики** holds everything about him. The character is previewed
live at the top of the page, so every change is visible straight away.

| Option | Values |
|--------|--------|
| Show the character | on / off — off leaves the chat exactly as it was before him |
| Outfit | The eighteen costumes, with their own names in both languages |
| T-shirt / Shorts / Shoes | Eight tones each, all drawn from one shared palette so any combination harmonises |
| Shades | Cyan, Pink, Green, Purple |
| Sunglasses | on / off — off reveals the bare Simpsons-style eyes underneath |
| Eyelashes | on / off — only visible with the shades off |
| Size (percent) | 50 % – 150 %, the firmware's SIZE row |
| Size (multiplier) | 60 % – 160 %, on top of the size the chat gives him |
| Tempo | 50 % – 200 %, the firmware's TEMPO row. It scales every mood, every bubble and every idle beat, so a slow character is slow all the way through |
| Chatter | on / off — whether he talks to himself when nothing is happening |
| Dragging | on / off — whether he may be picked up and carried |
| Placement | Session (the default: he stays where you put him until the app closes), Persistent (he stays there, next time included) or System (the chat puts him where it wants and he cannot be dragged at all) |
| Reset the look | puts every option back to its default |

Everything is stored under its own key (`potsik_outfit`, `potsik_shirt_color`,
…), so a field added later leaves the ones already stored alone. The size, the
tempo, the chatter switch, the dragging switch, the placement mode and the
place he was left in all have their own keys, so all of them survive the app
being closed.

## The costumes

All eighteen, with the firmware's names and the names the product gave them.
The app has no catch counter, so every costume is available from the start.

| Costume | Name | What it is |
|---------|------|-----------|
| `NONE` | Нет | The bare look: the tee, the shorts, the sneakers |
| `TANOOKI` | Тануки | The tanooki suit, with the ringed tail and the pointed ears |
| `UNICORN` | Единорог | Baby blue over baby pink, with a striped horn |
| `TINFOIL` | Фольга (Шапочка) | The crumpled foil hat, with the little propeller on top and the glint that runs round it |
| `SHADOW` | Тень (Ниндзя) | The ninja: the masked face, the red headband with its travelling tails |
| `PLUMBER` | Сантехник (Марио) | The red cap, the moustache, the white gloves, the overalls |
| `TALLBRO` | Высокий (Луиджи) | The same, in green and taller |
| `SPACE` | Космос (Астронавт) | The bubble helmet, the spacesuit on his arms, the boots, the drifting glint |
| `BLUEBLUR` | Синий вихрь (Ураган) | The blue fur, the quills, the white gloves, the red sneakers, the dust he kicks up |
| `CAPTAIN` | Капитан | The tricorn with the gold braid and the skull and crossbones |
| `CHROMEWING` | Хром крыло (Инсектоид) | The chrome wings, flapping behind him, with the shine that slides along them |
| `VOIDEYE` | Глаз пустоты | His head becomes a sphere of stars, with an iris that snaps to a new place and holds, and a lid that blinks |
| `PARKA` | Снежная парка (Кенни) | The snow parka: the hood, the mittens, the swinging drawstrings, the arms brought back over the hood |
| `YZZERD` | Йззерд (Волшебник) | The robe, the wide hat, the forked beard, the orbiting orb with its trail of motes, the gold star going the other way |
| `OVER9000` | Свыше 9000 (Гермес) | The gi, the eight swaying spikes of hair, the fringe, the scouter, the statics |
| `SHAMBLER` | Шамблер (Фрэнки) | Green-grey, the torn jeans and the torn shirt, the scar, the bandage, the flies |
| `TH3_0N3` | Избранный | The coat with its stirring hem, the round glasses with the code falling inside them |
| `STRIPES` | Полосатый | The prison stripes, the cap, the number on his chest, the iron cuff on his ankle |

## Key Files

| File | Purpose |
|------|---------|
| `composeApp/.../ui/potsik/PotsikBody.kt` | The character: the firmware's `drawBody()`, shape for shape |
| `composeApp/.../ui/potsik/PotsikCostumes.kt` | The eighteen costumes: the firmware's `drawOutfit()` |
| `composeApp/.../ui/potsik/PotsikEngine.kt` | His behaviour: the moods, the timings, the touch reactions, the idle roll |
| `composeApp/.../ui/potsik/PotsikBanter.kt` | The lines he says, English and Russian side by side |
| `composeApp/.../ui/potsik/PotsikBubble.kt` | The bubble above his head |
| `composeApp/.../ui/potsik/PotsikPalette.kt` | The colours, in the firmware's RGB565 |
| `composeApp/.../ui/potsik/PotsikShapes.kt` | The drawing primitives the firmware's `TFT_eSPI` calls became |
| `composeApp/.../ui/potsik/PotsikModel.kt` | The moods, the emote poses, the costumes and the settings |
| `composeApp/.../ui/potsik/PotsikLook.kt` | `PotsikLook` — everything the user can change |
| `composeApp/.../ui/potsik/PotsikAnimation.kt` | `PotsikAction` — the catalogue the agent's tool names |
| `composeApp/.../ui/potsik/PotsikAvatar.kt` | The character on his own, and the frozen-pose preview |
| `composeApp/.../ui/potsik/PotsikStage.kt` | Where he is: the anchors, the pinned position, the size, the engine, and the bounds (`clampPlacement`) that keep him on the screen |
| `composeApp/.../ui/potsik/PotsikOverlay.kt` | The one copy of him over the app, the bubble, the touch handling, and the anchors the layout puts where he belongs |
| `composeApp/.../tools/PotsikTool.kt` | The agent's tool — list the animations, play one, move him, resize him |
| `composeApp/.../ui/settings/PotsikiSettings.kt` | The Потсики settings tab — the live preview and every option |
| `composeApp/.../data/AppSettings.kt` | The `potsik_*` keys, `potsikLookFlow` and `setPotsikLook()` |
| `composeApp/.../ui/chat/composables/EmptyState.kt` | Потсик's place on the empty chat screen |
| `composeApp/.../ui/chat/composables/ToolMessage.kt` | Потсик's place in the working band — the band with no tile in it |
| `composeApp/.../ui/chat/composables/BotMessage.kt` | Потсик's place under an answer, talking while the TTS runs |
| `composeApp/.../ui/chat/ChatScreen.kt` | The overlay that draws him, and the phase he is in |
| `screenshotTests/.../PotsikScreenshotTest.kt` | Renders every costume and every animation, so a change to a single curve is visible |
| `scripts/generate-app-icon.py` | Draws the app icon — his head on the brand's purple plate, from the same numbers the app draws him with |
