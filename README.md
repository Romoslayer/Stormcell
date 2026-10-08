# Stormcell

Regional, moving weather for Minecraft servers (Fabric, NeoForge and Forge; Minecraft 1.20.1, 1.21.1, 26.2 and 26.3).

Vanilla weather is one switch for the whole world: everyone gets rain at the same moment. Stormcell replaces that with
storm systems that form, drift with the wind, build up, peak and fade away. It can pour on one player while another,
a few thousand blocks away, never sees a cloud. You can watch a storm come in: the sky goes grey, light rain starts,
it builds to a downpour or a thunderstorm, then eases off and clears as the storm moves on.

**Server-side only.** Players join with a completely unmodified client: no mod, no loader, no resource pack. Each
player is sent the weather of the spot they are standing in, through the same packets vanilla uses for its global
weather.

## What it does

- **Regional weather.** The world is split into large cells (256x256 blocks by default), each with its own
  temperature, humidity, pressure and wind. A 4x4 grid of samples per cell, blended smoothly, gives storms soft,
  irregular edges.
- **Moving storm systems.** Showers, wide bands of steady rain (weather fronts), and thunderstorms. They travel with
  the wind, merge when they run into each other, and (optionally) split.
- **Storm lifecycle.** Storms form, strengthen, hold, weaken and dissipate. Humid air feeds them, dry air starves
  them, and rain dries out the air behind them so the next storm is less likely for a while.
- **Calibrated to feel like vanilla.** Over plains it rains about 15% of the time, as in vanilla, in spells of around
  ten minutes; forests and oceans are wetter, jungles and swamps wettest and stormiest, savannas get rare storms and
  deserts stay all but dry. A player's weather does not depend on how many others are online or where they are.
- **Gradual intensity.** 0 = clear, 0.2 cloudy, 0.3 drizzle, 0.5 light rain, 0.7 heavy rain, 0.85 thunderstorm,
  1.0 severe. Clients are eased between rain strengths rather than switched, and jump straight to the new weather
  after a teleport.
- **Wind** steers storms and slowly changes direction and speed. It is part of the weather simulation only: it never
  pushes players, mobs, boats or projectiles.
- **Localized thunder and lightning.** Lightning only strikes under thunderstorms, more often the stronger the storm.
  Lightning rods and skeleton horse traps work as in vanilla (a rod draws strikes from up to 128 blocks away, so a
  rod near the edge of a thunderstorm can be struck).
- **Biome-aware.** Biome temperature and rainfall decide how often and how hard it rains. Modded biomes work from
  their own climate values; per-biome (or per-tag) overrides are in the config.
- **Dry weather.** Clients never draw rain in deserts, savannas or badlands, so those biomes get weather that suits
  them. Heat builds **dry thunderstorms**: the sky goes dark and lightning strikes, but no rain falls. By default dry
  lightning only flashes and thunders, so savannas do not burn down (`dryWeather.dryLightningStartsFires`).
  **Dust storms** sweep across deserts and badlands, about every three or four in-game days for 8-10 minutes: a
  gloomy sky and an unbroken rush of wind (vanilla's elytra flying sound, played lower and slower, one gust running
  into the next, and cut off as soon as you are out of the dust). They need wind, and
  settle quickly once they reach moist, green land. Rain storms drifting in from greener land rain on the edges
  and stop at the sand.
- **Elevation.** High ground is colder in the simulation (mountain storms bring snow, fewer thunderstorms). Rain vs.
  snow follows vanilla's height cooling, which is also what clients draw.
- **The world reacts locally.** Mobs get wet, fires go out, farmland gets watered, cauldrons fill, snow piles up,
  lightning strikes, bees head home, foxes and pandas react to thunder, monsters spawn in storm gloom and beds work
  under a thunderstorm, all based on the weather where they are.
- **Vanilla rules still apply.** With the `advance_weather` game rule off (`doWeatherCycle` before 26.x), or the
  game frozen with `/tick freeze` (not in 1.20.1, which has no `/tick`), the weather holds still. Sleeping through the
  night ends the storms over the sleepers (vanilla ends its world-wide rain); weather elsewhere carries on.
- **/weather still works.** `clear` removes every storm and keeps new ones away for the duration (10 minutes if none
  is given). `rain` and `thunder` start a storm over whoever ran the command, which stays there at full strength for
  the duration (vanilla's usual length if none is given), then drifts off and fades.
- **Persistent.** Storms, wind and recently rained-out regions survive restarts (`<world>/stormcell/`). A damaged
  save file is repaired where possible, and the original is kept next to it.
- **Performance-minded.** Weather is only simulated around players (and anything else that asks, like chunk
  loaders), updates run every few seconds rather than every tick, cell creation is budgeted per tick, and a gameplay
  question costs a few hash lookups (about 30 ns in benchmarks). A heavy test with 50 spread-out players and 265
  storms took about 1.6 ms per simulation step, once every five seconds.

## Configuration

`config/stormcell.toml` is created on first start, with a comment on every option. Highlights:

| Section | What it controls |
| --- | --- |
| `[general]` | on/off, simulation interval, cell size, sample resolution, which dimensions (Overworld only by default) |
| `[generation]` | rain / storm / thunder chance multipliers, minimum and maximum storm strength |
| `[stormBehavior]` | movement speed, growth and decay rates, merging and splitting |
| `[stormTypes.*]` | size, strength, duration, speed and shape of showers, rain systems and thunderstorms |
| `[wind]` | speed range, how fast it changes, local variation |
| `[atmosphere]` | temperature / humidity / pressure switches and influence |
| `[biomes]`, `[biomeOverrides."<id or #tag>"]` | biome climate influence and per-biome adjustments |
| `[elevation]` | height cooling |
| `[lightning]` | lightning frequency and threshold |
| `[dryWeather]` | dry thunderstorms, dry lightning fires, dust storms (biomes, frequency, wind sound, sky darkness) |
| `[thresholds]`, `[client]` | the intensity scale and what clients are shown at each step |
| `[gameplay]` | local mob behaviour, snow/cauldron scaling, `/weather` integration |
| `[performance]` | active radius, update rate of distant cells, cell creation budget, storm limits |
| `[seasons]` | the link with season mods |

Reload with `/stormcell reload`. A file that cannot be read is left untouched and the current settings stay in use;
if anything in it had to be ignored or corrected, the original is kept as `stormcell.toml.bak` before the file is
rewritten. The file uses a small, documented subset of TOML (see the comments in it).

## Commands (operators)

- `/stormcell info` - the simulated conditions where you stand
- `/stormcell spawn <shower|rain|thunderstorm|dust> [intensity]` - start a storm on top of you
- `/stormcell clear [radius]` - remove storms (all of them, or within a radius)
- `/stormcell client <player>` - the rain and thunder that player's client is showing (worked out from the weather
  packets actually sent to it, by Stormcell or vanilla) next to the weather simulated where they stand
- `/stormcell stats` - simulation timing and work counters
- `/stormcell reload` - reload the config

There is deliberately no forecast.

## Seasons

Stormcell works on its own. With Seasonfall installed, the seasons shape the climate
(temperature, humidity, how much it rains, how often storms form) and Stormcell still makes all the weather. Neither
mod depends on the other.

Other mods can do the same through `dev.romoslayer.stormcell.api.StormcellApi`: register a
`ClimateModifierProvider`, or read the local weather with `getLocalWeather` / `getPrecipitationIntensity` /
`getStormIntensity`.

## Limits

Stormcell only uses what an unmodified client already understands, so:

- A client has one rain strength and one thunder strength for its whole view. A storm visible on the horizon while
  it is dry overhead cannot be drawn; players see the weather where they are standing. Thunder from nearby lightning
  is heard as usual.
- "Cloudy" with no rain cannot be shown without some drizzle, so the client stays clear until the drizzle threshold,
  which is exactly where the server starts treating a spot as rainy.
- Clients decide rain vs. snow and where precipitation is drawn at all from the biome (deserts and savannas never
  show rain). Stormcell follows the same rules on the server so what happens matches what is seen.

On the server:

- Vanilla's world-wide "is it raining / thundering" flags read as clear in dimensions Stormcell runs. Every use of
  them in vanilla is redirected to the local weather; another mod reading them directly will see clear weather.
- The server's overall sky brightness stays at its clear-weather value: phantom spawning, daylight detectors and other
  "is it bright outside" checks do not darken under a storm. Monster spawning under thunderstorms and sleeping during
  them are handled locally.

## Building and testing

```bash
./gradlew build
```

Builds for 26.3; add `-Pmc=26.2`, `-Pmc=1.21.1` or `-Pmc=1.20.1` for the others. Jars land in
`<Loader>/build/<generation>/libs` (`mc26`, `mc21` or `mc20`, e.g. `Fabric/build/mc26/libs`). Code shared by every
version is in `src/main`; what differs between Minecraft generations is in `src/mc26`, `src/mc21` and `src/mc20`. The
build runs the unit tests (`Common/src/test`). Two longer runs are separate:

```bash
./gradlew :Common:calibrate
```

```bash
./gradlew :Common:benchmark
```

`calibrate` simulates 30 in-game days per scenario and prints how often it rains and thunders by biome, simulation
interval and player count; `benchmark` prints simulation timings under load.
