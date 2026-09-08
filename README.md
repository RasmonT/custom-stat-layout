# Custom Stat Layout

A RuneLite plugin that publishes the four orb values as JSON on `127.0.0.1`, so a
browser-source overlay can draw its own orbs instead of cropping the client's.

This exists because the game paints its icons into the same pixels as the orb
fill. Cropping the client can never separate them. Reading the numbers and
drawing the orb from scratch can, and it also frees the design completely: the
colour, the shape and the animation become a web page, not a video render.

## Run it

Double-click `RUN.bat`. It starts the normal RuneLite client with this plugin
compiled in — same settings, same Plugin Hub plugins. Then enable **Custom Stat
Layout** in the plugin list.

The first run downloads Gradle and the client and takes a few minutes. It needs
a JDK 11 or newer; RuneLite's own bundled runtime is a JRE and cannot compile:

    winget install EclipseAdoptium.Temurin.21.JDK

Open a new terminal window afterwards so the changed PATH is picked up.

## Jagex account

A client started from a build like this one is not launched by the Jagex
Launcher, so it has no session to log in with. RuneLite documents the way
around it in
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts):

1. Make sure the RuneLite launcher is version 2.6.3 or newer.
2. Run **RuneLite (configure)** from the Start menu.
3. In **Client arguments** add `--insecure-write-credentials`, then Save.
4. Launch RuneLite the normal way, through the Jagex Launcher. It writes your
   session to `%USERPROFILE%\.runelite\credentials.properties`.
5. Now `RUN.bat` logs in with those saved credentials.

That file logs into your account without a password, so treat it like a
password: do not share it, do not commit it. When you are done developing,
delete it to put RuneLite back to normal, and remove
`--insecure-write-credentials` from the launcher arguments. If it ever leaks,
**End sessions** under account settings on runescape.com invalidates it.

This is only needed while the plugin is run from source. Once it is on the
Plugin Hub it installs into your normal Jagex-launched client like any other
plugin, and none of this applies.

## What it serves

`http://127.0.0.1:5030/stats.json`

```json
{
  "ok": true, "loggedIn": true,
  "hp": 37, "hpMax": 99,
  "prayer": 11, "prayerMax": 77,
  "run": 63, "runRaw": 6321,
  "spec": 25, "specRaw": 250,
  "souls": 0, "soulsMax": 5,
  "specMode": "percent",
  "hpRegen": 0.24, "specRegen": 0.78,
  "lightbearer": false, "rapidHeal": false,
  "praying": true, "tickTs": 1757289599400, "tickMs": 600, "ticks": 4213,
  "specArmed": false, "stamina": false, "weight": 12,
  "poison": "none", "diseased": false,
  "ts": 1757289600000
}
```

- `run` is whole percent; `runRaw` is hundredths, which is what the client
  actually tracks. The overlay uses the raw value so the liquid moves smoothly
  instead of stepping a whole percent at a time. `spec` / `specRaw` are the same
  idea in tenths.
- `specMode` is `"souls"` while a Soulreaper axe is wielded, because the game
  replaces the number in the special attack orb with the soul stack count. An
  overlay that mirrors the orb should show `souls` / `soulsMax` in that case.
- `loggedIn: false` is sent on the login screen and during a world hop, with no
  numbers. An overlay should hold its last frame rather than flash empty orbs.
- `hpRegen` and `specRegen` are how far through its regeneration cycle each orb
  is, 0 to 1 — the sweep the game draws around the orb, not a number the player
  reads. Hitpoints regenerate one point every 100 ticks, halved by the Rapid
  Heal prayer; special attack regenerates 10% every 50 ticks, halved by a
  Lightbearer ring, and does not tick at all while the bar is full. `hpRegen` is
  0 at full health, because that is where the game hides it (there is a setting
  to keep it), and runs backwards while overhealed, because the next tick takes
  a point away instead of adding one. This is the same arithmetic as RuneLite's
  own Regeneration Meter plugin, so the two agree tick for tick.

Everything the game shows in these orbs changes on a game tick, so the snapshot
is rebuilt from tick and stat/var events. There is no faster data to have.

- `tickTs` is the wall-clock time of the last game tick and `tickMs` the tick
  length. An overlay on the same machine reads the same clock, so it can work
  out how far through the current tick it is and animate in step with the game
  instead of in step with its own polling. That is what the prayer flick
  metronome needs, and polling four times a tick could never give it.
- `praying` is true while any prayer is on. RuneLite hides its flick helper when
  none is, and an overlay that wants to behave the same needs to know.
- `specArmed`, `stamina`, `poison` and `diseased` are the states the game paints
  onto the orbs themselves: the special attack orb lights up when the attack is
  armed, the run orb shows a stamina potion running, and the hitpoints orb is
  tinted for poison, venom or disease. `poison` is one of `none`, `immune`,
  `poison`, `venom`. A layout that wants to look like the game needs these; one
  that does not can ignore them.
- `ticks` counts game ticks seen. If it stays at 0 while the other values move,
  tick events are not reaching the plugin. `regenError` appears only if the
  regeneration maths ever throws.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| Port | 5030 | The loopback port. Change it only if something else already uses it. |
| HP sweep at full health | off | The game hides the hitpoints sweep once you are at full health. Turn this on to keep it running anyway. |

## Using it in OBS

Add a Browser Source pointing at your overlay page — a local file or a hosted
one, OBS loads it either way:

- `file:///C:/path/to/overlay.html?src=1`
- `https://example.com/my-layout?src=1`

Width 260, height 490, and tick *Shutdown source when not visible* off so it
keeps polling while you switch scenes.

`?src=1` is shorthand for the default address above; `?src=<url>` points at any
endpoint serving the same shape. If nothing answers, the page keeps whatever the
other URL parameters set, so a dead feed degrades to a static overlay rather
than a broken one.

## Security

The socket binds to the loopback address only, so nothing outside this machine
can reach it — verified by connecting from the machine's own LAN address and
being refused.

The server has exactly one response: the JSON above. It reads nothing from disk
and serves no files, so there is no path to traverse and nothing to leak.

Responses carry `Access-Control-Allow-Origin: *`, which means any page open in
your browser while the game runs can read them. That is deliberate — an overlay
has to be loadable from a local file, another port, or a website. It is also why
nothing identifying is in the payload: no account name, no world, no location.
Only the four numbers already on screen in the client.

## Publishing it

`runelite-plugin.properties` is filled in, so this is ready to be submitted to
the RuneLite Plugin Hub as a repository of its own. `bank-bridge` is the
precedent for a plugin that opens a loopback socket: it was accepted with a
warning banner describing what it exposes.
