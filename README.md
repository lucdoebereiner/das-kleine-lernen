# Das kleine Lernen (2022)

Sound installation for five algorithmic agents. Revised code, October 2026.

```
classes/KleinesLernen.sc     KleinesLernen (one agent) + KleinesLernenRoom (simulated room)
run_ensemble.scd             N agents in parallel on one machine + multichannel recording
pretrain.scd                 fill the agents' memory offline before a session
tests/test_algorithm.scd     offline tests of the learning code (no server needed)
tests/measure_knn.scd        measures how well the inverse model works on the real synth (~7 min)
original/                    the 2023 sources (SmallLearning.sc, session script)
```

## Installation

Requires SuperCollider (3.13 or later) and the [FluCoMa](https://www.flucoma.org) extension (for `FluidMFCC`).
Make the classes known to SuperCollider, e.g. by linking the folder into your Extensions directory, then recompile the class library:

```
ln -s "$(pwd)/classes" ~/.local/share/SuperCollider/Extensions/das_kleine_lernen   # Linux
```
The new class is called `KleinesLernen`, so it can't clash with the old `SmallLearning`.

## Running

Optionally, once beforehand (about 2.5 min, writes `~/recordings/das_kleine_lernen/pretrained.table`):

```
sclang pretrain.scd 3000
```

Then, from a shell (it quits by itself when the recording is done):

```
sclang run_ensemble.scd 5 1200            # 5 agents, 20 min, virtual room
sclang run_ensemble.scd 4 600 mics                      # mics 1-4 -> speakers 1-4
sclang run_ensemble.scd 4 600 mics 2,3,4,5 0,1,2,3      # explicit channels (counted from 0)
sclang run_ensemble.scd 4 0 mics                        # 0 = record until ~stopAll.()
```

### With real speakers and microphones (`mics`)

Agent i listens on hardware input `micChannels[i]` and plays on hardware output `speakerChannels[i]`.
The recording has 2N channels: the N agent signals as sent to the speakers, then the N mic signals (`~recordMics = false` leaves the mics out).
On startup the script prints the routing (`agent1: mic in 1 -> speaker out 1`, counted from 1).

- `~device` in the script selects the audio interface (nil = system default).
- With PipeWire/JACK, check (e.g. in qpwgraph) that SuperCollider's inputs and outputs are connected to the interface's channels. If the inputs aren't connected, the agents print `random (heard nothing)` every cycle.
- `~micGain` (default 1.5, as in the installation) is the gain before the analysis. The analysis ignores input below amplitude 0.001. If the agents keep hearing nothing even though the inputs are connected, raise it.
- The agents also adapt their own volume to the level they hear: a louder room makes them quieter.

You can also evaluate the block in `run_ensemble.scd` in the IDE or scnvim and stop it with `~stopAll.()`.
Recordings go to `~/recordings/das_kleine_lernen/`, one channel per agent, as 32-bit float wav.
The learned tables are saved there under `tables/`. Set `~loadTables = true` to continue from them.
Agents without a saved table of their own start from `pretrained.table` (`~usePretrained = false` starts them empty, as in 2022).

Single agent with your own buses:

```supercollider
s.options.blockSize = 1;   // required (see below)
s.boot;
a = KleinesLernen(\a1, inBus, outBus).start;   // inBus: e.g. s.options.numOutputBusChannels + micChannel
a.stop;
```

**The server must run with `blockSize = 1`.** The wavefolder's LocalIn/LocalOut feedback loop is one block long, so the sound depends on it.
Five agents use about 10% of one CPU core.

### Virtual room

In `\virtual` mode, `KleinesLernenRoom` places the agents on a circle (`~roomRadius`).
Each agent's input is the sum of all outputs, each delayed by distance / 343 m/s, attenuated by 1/d and slightly low-passed.
`~roomGain` (default 3) acts as a mic preamp. `~roomNoise` adds a faint pink room tone. On its own the tone stays below the analysis gate, but it keeps the empty upper mel bands from dropping to digital silence, which otherwise gives extreme MFCC values.
The monitor plays a stereo `Splay` mix on outs 0/1. The recording contains the individual agents.

## The algorithm

Each cycle has two phases:
1. **Listen:** analyse the input for 2–6 s. Record the mean and standard deviation of 12 MFCCs as the *target*.
2. **Play:** choose 7 wavefolder parameters (shift, foldThresh, freq1, freq2, width1, width2, delay). Play for `dur` seconds and analyse the agent's *own dry output*. Store `[parameters, means, devs]` in the table.

To choose parameters, the agent looks up the table entry whose sound is closest to the target and takes its parameters with a small random step (a nearest-neighbour inverse model).
It tries a completely new random sound when the error between target and result is too large, or by chance (8%).
The error also sets the next duration: the closer the match, the longer it plays (20–90 s).

## What was changed

Bugs in `SmallLearning.sc`:

- **The kNN weighting was not inverse-distance weighting.** `weights.squared.invert` in SC is `(min+max) − x`, not `1/x`. The parameters were also averaged linearly, so for example 100 Hz and 5000 Hz averaged to 2550 Hz.
  This is now fixed (1/d² weights, averaging in log space for frequencies, shift and delay), but averaging is off by default: see the measurements below.
- **Crash on a fresh start:** with an empty table, `knn` → `averageCandidates([])` failed. The 2023 setup only worked because it loaded a saved table.
- **Crash or stale target when nothing was heard:** the analysis is gated (amp > 0.001). With no frames, `targetAnalysis` was nil on the first cycle (crash) and stale afterwards. It is now reset every cycle, and the agent plays something random when it heard nothing.
- **Stale own analysis:** if the agent's own sound was silent (some parameter sets make the feedback die out), the error was computed against the previous round's analysis. Now no error is computed in that case, and the next choice is random, so silent settings aren't repeated.
- `foldParameters` was shifted by one index (`pars[4] = pars[5]…`, `pars[7]` = nil). It was unused, but it crashed when called. It is replaced by folding in the normalised space.
- Multiple instances: the OSC paths were tied to the output channel (`'/amp' ++ channel`), and mic input and speaker output were the same index. Replies are now filtered by node ID, and `inBus` and `outBus` are independent arguments.
- The analysis was a closure function-synth whose bus was never freed. OSCdefs were re-created for every collection. There was no way to stop an agent. Now there are proper SynthDefs, one group per agent, and `stop` frees everything.
- Tables are saved with `writeArchive`, because `asCompileString`/`compile` is slow and fragile for large tables. Old `.scd` tables still load. Table size is capped at 5000 entries (oldest are forgotten).

Improvements to the learning:

- **Nearest neighbour instead of averaging (`k = 1`).** The synth is chaotic, so the average of three neighbours lands between regimes and sounds like none of them. Measured below.
- **Local exploration:** the chosen parameters get a small Gaussian step (std dev up to 0.02 of each range, scaled by the error). Previously, the same target always gave the same answer, so new table points came only from the 8% random jumps.
- **Pre-training (`pretrain.scd`):** random settings are rendered faster than real time and analysed exactly like an agent's own sound. The offline and live analyses of the same settings differ by 0.3–1.3, unrelated sounds by 120–170. Live, an agent gains about 100 entries per hour; 3000 pre-rendered sounds take about 2.5 minutes. In a 6-minute ensemble test the live errors dropped from mostly 100–250 (empty start) to a median of about 40.
- **Adaptive error thresholds** (`adaptive = true`, default): the reset threshold (1.25 × median of the last 30 errors) and the error → duration mapping (10th–90th percentile) are relative to each agent's own error history. There are no error-based resets during warm-up (the first 5 errors).
  Reason: the fixed thresholds (reset above 45, durations over errors 10–90) were tuned to the physical room. Measured here, an agent hearing *its own* sound through speaker → room → input chain already gets errors of 15–150. With the fixed values, almost every choice became a random reset (in a 6-min test: 38 random vs. 11 kNN; with the adaptive version, over 8 min: 11 random vs. 42 kNN).
  For the old behaviour: `agent.adaptive = false` (`resetErr`, `errRange`).

## Measurements (`tests/measure_knn.scd`)

2000 training and 200 held-out target sounds, all rendered offline from random settings. For each target, the parameters chosen by each strategy were rendered again and compared with the target. 1.0 = the median distance between two unrelated sounds.

| strategy | table 100 | table 500 | table 2000 | silent results |
|---|---|---|---|---|
| nearest (k=1), raw distance | 0.47 | 0.33 | 0.26 | 0 |
| nearest (k=1), scaled distance | 0.42 | 0.30 | 0.24 | 0 |
| average of 3 | 0.64–0.66 | 0.57–0.61 | 0.63–0.67 | 4–14 of 183 |
| average of 5 | 0.71 | 0.69 | 0.67 | 10–14 |
| random | 1.02 | | | |

(The k=1 rows use the scaled measure. Measured with the raw distance, k=1 raw scores 0.16 and k=1 scaled 0.18 at table 2000.)

- **Averaging is clearly worse and doesn't improve with more data.** Hence `k = 1`. A parameter step of 0.005 (normalised) changes the sound by ~7% of an unrelated-sound distance, 0.02 by ~15%, 0.05 by ~40%, 0.1 by ~55%. So the synth is not smooth enough to average, and the exploration step is kept small.
- **Feature scaling:** coefficient 1 (spectral tilt) varies with a spread of 81, the others by 12–31, so the raw distance is dominated by brightness. Scaling each coefficient (`normalizeFeatures = true`) wins on the scaled measure and loses on the raw one, each by ~10%. This doesn't show that one is better; it's a choice of what "similar" means. Default: raw, as in the original.
- **Same settings → same sound:** rendering the same parameters twice gives identical analyses; the synth is deterministic. All unpredictability comes from neighbouring settings sounding different.
- About 11% of random settings are silent (the feedback dies out).

These measure an agent imitating sounds it could make itself (dry). In the room the targets arrive through speaker, air, mic, 3 kHz low-pass and compander, which the dry memory never contains. That gap (an agent's own sound through the chain scores 15–150 raw) is part of the piece and is not modelled.

Unchanged: the wavefolder synth, the listening chain (×1.5, LPF 3 kHz, compander), FluidMFCC with 13 coefficients from 1, the 0.1 weight of the standard deviations, the cycle timing, and the room-level → output-level coupling.

Tunable per agent: `k`, `normalizeFeatures`, `randomProb`, `explore`, `resetFactor`, `maxTableSize`, `inGain`, `analysisRate`, `stdWeight`, `verbose`.

Timing (defaults = the installation): `durRange` (playing time, 20–90 s), `listenRange` (listening/pause, 3–15 s) and `timeJitter` (random variation per cycle, e.g. 0.25 = ±25%).
With the defaults, agents whose errors are similar get identical cycle lengths and can lock into phase, entering and pausing together. A little jitter lets them drift apart.
