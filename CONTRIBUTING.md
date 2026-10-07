# Contributing

Notes for running Custom Stat Layout from source and sending changes. Installing the
plugin from the Plugin Hub needs none of this.

## Run it from source

Double-click `RUN.bat`. It starts the normal RuneLite client with this plugin
compiled in - same settings, same Plugin Hub plugins. Then enable **Custom Stat
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

## Project layout

- `src/main/java/com/imthec4/customstatlayout/` - the plugin: `CustomStatLayoutPlugin`
  gathers the values, `CustomStatLayoutServer` serves them on 127.0.0.1,
  `CustomStatLayoutPanel` is the side panel, `CustomStatLayoutConfig` the settings.
- `src/test/java/.../CustomStatLayoutLauncher.java` - what `RUN.bat` / `gradlew run`
  starts: the RuneLite client with this plugin loaded.
- `runelite-plugin.properties` - Plugin Hub metadata; `version=` is the release
  number.

## Sending a change

Open a pull request against `master`. Keep the plugin's rules: it binds to the
loopback address only, serves exactly one JSON document, reads nothing from disk,
and puts nothing identifying in the payload (no account name, world or location).
Code comments in English.

## Releasing to the Plugin Hub

Bump `version=` in `runelite-plugin.properties` (and `version` in `build.gradle`),
commit, then point the `commit=` line of `plugins/custom-stat-layout` in a
[runelite/plugin-hub](https://github.com/runelite/plugin-hub) fork at the new
commit hash and open a pull request there. `bank-bridge` is the
precedent for a plugin that opens a loopback socket: it was accepted with a
warning banner describing what it exposes.
