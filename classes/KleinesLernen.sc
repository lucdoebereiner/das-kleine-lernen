// Das kleine Lernen (2022) — Luc Döbereiner
//
// One agent: a feedback wavefolder that alternates between listening (analysing
// its input bus) and playing. While playing it analyses its own (dry) output and
// stores [parameters, mfccMeans, mfccDevs] in a table. To decide what to play
// next it looks up the k entries whose sound is closest to what it just heard
// and interpolates their parameters (k-nearest-neighbour inverse model).
//
// Revised version of SmallLearning.sc (2023). See README.md for the changes.
//
// The server must run with blockSize = 1: the wavefolder's LocalIn/LocalOut
// feedback loop is one block long and the sound depends on it.

KleinesLernen {
	classvar <numParameters = 7, <mfccSize = 13;
	// [min, max, warp] — used to interpolate/perturb parameters in a normalised space
	classvar <parRanges;
	classvar <parNames;

	var <name, <inBus, <outBus, <server;
	var <table, <>maxTableSize = 5000;
	// k = 1: averaging the parameters of several neighbours lands between regimes of the
	// (chaotic) synth and sounds like neither — measured worse (tests/measure_knn.scd)
	var <>k = 1;                 // number of nearest neighbours
	var <>randomProb = 0.08;     // chance of trying a completely new sound
	var <>resetErr = 45;         // above this error: try a completely new sound (adaptive = false)
	var <>errRange = #[10, 90];  // error range mapped to durations 90 .. 20 s (adaptive = false)
	var <>adaptive = true;       // derive both from the agent's own recent errors instead
	var <>resetFactor = 1.25;    // adaptive: reset if error > resetFactor * median of recent errors
	var <>errHistorySize = 30;
	var <>durRange = #[20, 90];   // playing time in s: best match -> durRange[1], worst -> durRange[0]
	var <>listenRange = #[3, 15]; // listening (pause) time in s, 0.35 * playing time clipped to this
	var <>timeJitter = 0;         // random variation of both per cycle (0.25 = +-25%), keeps agents out of phase
	var <>explore = 0.02;        // max std dev of the local perturbation (normalised space)
	var <>inGain = 1.5;          // input gain before the analysis chain
	var <>analysisRate = 3;      // analysis frames per second
	var <>stdWeight = 0.1;       // weight of the std devs in the distance
	var <>normalizeFeatures = false; // neighbour search: scale each coefficient by its spread in the table
	var <>verbose = true;
	var <>autosavePath;          // if set, the table is written here after every cycle

	var <group, <analysisSynth, <playSynth, <privBus, <task, frames, oscKeys;
	var <err = 40.0, <playAmp = 0.2, <dur = 8, <errHistory, <silentCount = 0, listenJitter = 1;
	var <currentParameters, <targetAnalysis, <currentAnalysis, <lastChoice, featureScale;

	*initClass {
		parNames = [\shift, \foldThresh, \freq1, \freq2, \width1, \width2, \delay];
		parRanges = [
			[1.1, 4, \exp],        // shift
			[0.05, 0.7, \lin],     // foldThresh
			[90, 5000, \exp],      // freq1
			[90, 5000, \exp],      // freq2
			[0.03, 0.3, \lin],     // width1
			[0.03, 0.3, \lin],     // width2
			[0.00001, 0.1, \exp]   // delay
		];
	}

	*new { |name, inBus = 0, outBus = 0, server|
		^super.new.init(name, inBus, outBus, server)
	}

	init { |argName, argInBus, argOutBus, argServer|
		name = (argName ? "kl%".format(UniqueID.next)).asSymbol;
		inBus = argInBus.asControlInput;
		outBus = argOutBus.asControlInput;
		server = argServer ? Server.default;
		table = [];
		errHistory = [];
	}

	*sendSynthDefs { |server|
		this.synthDefs.do { |def| def.send(server ? Server.default) }
	}

	*synthDefs {
		^[
		// the sound: feedback wavefolder (unchanged from the installation)
		SynthDef(\kleinesLernenRana, { arg out = 0, outListen = 0, attack, sustain, decay, amp = 0.5, shift = 1.8, delay = 0.0001,
			foldThresh = 0.31, freq1 = 1321, freq2 = 343, width1 = 0.2, width2 = 0.2, mix = 0.5;
			var inRaw = LocalIn.ar(1) * 1.2;
			var in = Compander.ar(inRaw, inRaw, 0.5, 1, 0.5) * 1.5;
			var rm = (in*in).lag2(0.2);
			var env = EnvGen.ar(Env([0,1,1,0],[attack, sustain, decay], [4,1,-4]), doneAction: 2);
			var fold = ((in* shift + ((rm/0.05).clip(0,1).linlin(0,1,0.4,0.01).lag2(4)) ).fold(foldThresh.neg, foldThresh)*(1/foldThresh));
			var fold1 = BBandPass.ar(fold, freq1, width1);
			var fold2 = BBandPass.ar(fold, freq2, width2);
			fold = SelectX.ar(mix, [fold1, fold2]);
			fold = fold * (0.85 + (env*0.2));
			LocalOut.ar(DelayC.ar(fold, 0.2, delay));
			fold = HPF.ar(fold, 20);
			Out.ar(out, fold * 0.5);
			Out.ar(outListen, LPF.ar(fold * env * amp, 4000) * -10.dbamp);
		}),

		// the ear: inSelect 0 = input bus (the room), 1 = own dry sound
		SynthDef(\kleinesLernenAnalysis, { arg inBus = 0, privBus = 0, inSelect = 0, inGain = 1.5, rate = 3;
			var audioIn, synthIn, in, amp, mfcc;
			audioIn = InFeedback.ar(inBus) * inGain;
			audioIn = LPF.ar(audioIn, 3000);
			audioIn = Compander.ar(audioIn, audioIn, 0.2, 1, 0.3) * 2;
			synthIn = In.ar(privBus);
			in = Select.ar(inSelect, [audioIn, synthIn]);
			amp = Amplitude.kr(in);
			mfcc = FluidMFCC.kr(in, 13, startCoeff: 1); // drop first band
			SendReply.kr(Impulse.kr(rate) * (amp > 0.001), '/kleinesLernen/mfcc', mfcc);
			SendReply.kr(Impulse.kr(1) * (1 - inSelect), '/kleinesLernen/amp', amp.lag2ud(0.1, 5));
		}),

		// the ear for offline (NRT) rendering: same analysis of the dry sound, but
		// the frames go to a buffer (frame i = analysis at ~ (i + 1) / rate seconds),
		// with the amplitude as the last channel so the gate can be applied afterwards
		SynthDef(\kleinesLernenNRTAnalysis, { arg inBus = 0, buf = 0, offset = 0, frames = 60, rate = 3;
			var in = In.ar(inBus);
			var amp = Amplitude.kr(in);
			var mfcc = FluidMFCC.kr(in, 13, startCoeff: 1);
			var index = (PulseCount.kr(Impulse.kr(rate)) - 1).clip(0, frames - 1);
			BufWr.kr(mfcc ++ [amp], buf, offset + index, loop: 0);
		})
		]
	}

	// ---------------------------------------------------------------- parameters

	*rndParameters {
		var pars = Array.fill(numParameters, { 0.0 });
		pars[0] = exprand(1.1, 4);
		pars[1] = rrand(0.05, 0.7);
		pars[2] = exprand(90, 5000.0);
		pars[3] = exprand(90, 5000.0);
		pars[4] = linrrand(0.03, 0.3);
		pars[5] = linrrand(0.03, 0.3);
		pars[6] = exprand(0.00001, 0.1);
		^pars
	}

	*normParameters { |pars|
		^pars.collect { |p, i|
			var lo, hi, warp;
			#lo, hi, warp = parRanges[i];
			if (warp == \exp) { p.explin(lo, hi, 0, 1) } { p.linlin(lo, hi, 0, 1) }
		}
	}

	*unnormParameters { |normed|
		^normed.collect { |x, i|
			var lo, hi, warp;
			#lo, hi, warp = parRanges[i];
			if (warp == \exp) { x.linexp(0, 1, lo, hi) } { x.linlin(0, 1, lo, hi) }
		}
	}

	// weighted mean in the normalised space (i.e. geometric mean for frequencies etc.)
	*averageParameters { |parsList, weights|
		var normed = parsList.collect { |p| this.normParameters(p) };
		weights = (weights ?? { 1 ! parsList.size }).normalizeSum;
		^this.unnormParameters((normed * weights).sum)
	}

	// gaussian step in the normalised space, reflected at the range borders
	*perturbParameters { |pars, sigma|
		^this.unnormParameters(this.normParameters(pars).collect { |x| (x + 0.0.gauss(sigma)).fold(0, 1) })
	}

	// ---------------------------------------------------------------- analysis / learning

	*meanDev { |frms|
		var n, means, devs;
		frms = frms.select { |f| f.size == mfccSize and: { f.every { |x| x.abs < 1e6 } } }; // drop NaN/inf frames
		n = frms.size;
		if (n == 0) { ^nil };
		means = frms.sum / n;
		devs = (frms.collect { |f| (f - means).squared }.sum / n).sqrt;
		^[means, devs]
	}

	distanceMFCC { |v1, v2|
		^(v1 - v2).squared.sum.sqrt
	}

	compareMFCC { |means1, devs1, means2, devs2|
		^this.distanceMFCC(means1, means2) + (this.distanceMFCC(devs1, devs2) * stdWeight)
	}

	table_ { |newTable|
		table = newTable;
		featureScale = nil;
	}

	// spread (std dev) of each coefficient over the table, for means and devs separately.
	// Without it coefficient 1 (spectral tilt, values ~100-250) dominates the distance.
	featureScale {
		if (normalizeFeatures.not or: { table.size < 10 }) { ^[1 ! mfccSize, 1 ! mfccSize] };
		^featureScale ?? {
			featureScale = [1, 2].collect { |j|
				table.collect { |entry| entry[j] }.flop.collect { |col|
					var m = col.mean;
					(col - m).squared.mean.sqrt.max(1e-6)
				}
			}
		}
	}

	// distance used to find neighbours (scaled); the error uses the raw compareMFCC
	searchDistance { |means1, devs1, means2, devs2|
		var scale = this.featureScale;
		^this.distanceMFCC(means1 / scale[0], means2 / scale[0])
			+ (this.distanceMFCC(devs1 / scale[1], devs2 / scale[1]) * stdWeight)
	}

	// parameters whose recorded sound is closest to [means, devs], or nil if the table is empty
	knn { |n, means, devs|
		var candidates;
		if (table.isEmpty) { ^nil };
		candidates = table.collect { |entry|
			[this.searchDistance(entry[1], entry[2], means, devs), entry[0]]
		};
		if (n == 1) { ^candidates.minItem { |c| c[0] }[1].copy };
		candidates = candidates.sort { |a, b| a[0] < b[0] }.keep(n);
		^this.averageCandidates(candidates)
	}

	// inverse-squared-distance weighting
	averageCandidates { |candidates|
		var exact = candidates.detect { |c| c[0] <= 1e-9 };
		var weights;
		if (exact.notNil) { ^exact[1].copy };
		weights = candidates.collect { |c| c[0].squared.reciprocal };
		^this.class.averageParameters(candidates.collect { |c| c[1] }, weights)
	}

	nextParameters {
		var pars, how;
		case
		{ targetAnalysis.isNil } { pars = this.class.rndParameters; how = "random (heard nothing)" }
		{ table.isEmpty } { pars = this.class.rndParameters; how = "random (empty table)" }
		{ silentCount > 0 } { pars = this.class.rndParameters; how = "random (own sound was silent)" }
		{ err > this.resetThreshold } { pars = this.class.rndParameters; how = "random (error > %)".format(this.resetThreshold.round(0.1)) }
		{ randomProb.coin } { pars = this.class.rndParameters; how = "random (coin)" }
		{
			pars = this.knn(k, targetAnalysis[0], targetAnalysis[1]);
			pars = this.class.perturbParameters(pars, explore * (err / this.resetThreshold.min(resetErr * 2)).clip(0.2, 1));
			how = "knn"
		};
		lastChoice = how;
		this.post("next: % %".format(how, pars.collect(_.round(0.0001))));
		^pars
	}

	// the error scale depends on the room, microphones and speakers, so by default
	// the thresholds are relative to the agent's own recent errors
	prErrPercentile { |q|
		var sorted = errHistory.copy.sort;
		^sorted[((sorted.size - 1) * q).round.asInteger]
	}

	resetThreshold {
		if (adaptive.not) { ^resetErr };
		// warm-up: no resets until there is an error history
		^if (errHistory.size >= 5) { this.prErrPercentile(0.5) * resetFactor } { inf }
	}

	currentErrRange {
		var lo, hi;
		if (adaptive and: { errHistory.size >= 5 }) {
			lo = this.prErrPercentile(0.1);
			hi = this.prErrPercentile(0.9).max(lo + 1);
			^[lo, hi]
		};
		^errRange
	}

	addEntry { |pars, analysis|
		table = table.add([pars, analysis[0], analysis[1]]);
		if (table.size > maxTableSize) { table = table.drop(table.size - maxTableSize) };
		featureScale = nil;
	}

	// ---------------------------------------------------------------- offline rendering

	// Render the dry sound of each parameter set faster than real time (NRT, in
	// `jobs` parallel scsynth processes) and analyse it the way an agent analyses
	// its own sound while playing. Call inside a Routine.
	// Returns one [means, devs] per parameter set (nil if the sound was silent).
	*renderAnalyses { |paramsList, dur = 20, jobs = 8, voices = 16, rate = 3, sampleRate = 48000|
		var framesPer = (dur * rate).asInteger;
		var chunks = paramsList.clump((paramsList.size / jobs).ceil.max(1).asInteger);
		var results = Array.newClear(chunks.size), cond = Condition(false), done = 0;
		var options = ServerOptions.new
			.blockSize_(1).numOutputBusChannels_(1).numInputBusChannels_(0)
			.numAudioBusChannels_(128 + voices).memSize_(2 ** 16);
		chunks.do { |chunk, ci|
			var base = PathName.tmp +/+ "kleinesLernen_nrt_%_%".format(UniqueID.next, ci);
			var score = this.prNRTScore(chunk, dur, rate, voices, base ++ "_mfcc.wav");
			Score.recordNRT(score, base ++ ".osc", base ++ "_out.wav", nil, sampleRate, "WAV", "float",
				options, "", nil, {
					results[ci] = this.prReadNRTAnalyses(base ++ "_mfcc.wav", chunk.size, framesPer);
					[".osc", "_out.wav", "_mfcc.wav"].do { |ext| File.delete(base ++ ext) };
					done = done + 1;
					if (done == chunks.size) { cond.test = true; cond.signal };
				});
		};
		cond.wait;
		^results.flatten(1)
	}

	// Fill a table without a room: n random sounds, rendered offline. Call inside a Routine.
	*pretrain { |n = 3000, path, dur = 20, jobs = 8|
		var pars = n.collect { this.rndParameters };
		var analyses = this.renderAnalyses(pars, dur, jobs);
		var newTable = [pars, analyses].flop.select { |pa| pa[1].notNil }.collect { |pa|
			[pa[0], pa[1][0], pa[1][1]]
		};
		path !? { newTable.writeArchive(path.standardizePath) };
		^newTable
	}

	*prNRTScore { |paramsList, dur, rate, voices, bufPath|
		var framesPer = (dur * rate).asInteger, slotDur = dur + 0.5;
		var slots = paramsList.clump(voices), score = List.new, endTime;
		score.add([0.0]
			++ this.synthDefs.collect { |def| [\d_recv, def.asBytes] }
			++ [[\b_alloc, 0, paramsList.size * framesPer, mfccSize + 1], [\g_new, 1, 0, 0]]);
		slots.do { |slotPars, s|
			var time = 0.01 + (s * slotDur), starts = List.new, frees = List.new;
			slotPars.do { |pars, v|
				var i = (s * voices) + v, g = 1000 + (i * 3), bus = 64 + v;
				starts.add([\g_new, g, 1, 1]);
				starts.add([\s_new, \kleinesLernenRana, g + 1, 0, g,
					\attack, dur / 3, \sustain, dur / 3, \decay, dur / 3, \amp, 0, \out, bus, \outListen, 0]
					++ [parNames, pars].flop.flatten);
				starts.add([\s_new, \kleinesLernenNRTAnalysis, g + 2, 1, g,
					\inBus, bus, \buf, 0, \offset, i * framesPer, \frames, framesPer, \rate, rate]);
				frees.add([\n_free, g]);
			};
			score.add([time] ++ starts);
			score.add([time + dur] ++ frees);
		};
		endTime = 0.01 + (slots.size * slotDur);
		score.add([endTime, [\b_write, 0, bufPath, "wav", "float"]]);
		score.add([endTime + 0.1, [\c_set, 0, 0]]);
		^score.asArray
	}

	*prReadNRTAnalyses { |path, n, framesPer|
		var sf = SoundFile.new, data, frms;
		if (sf.openRead(path).not) { "KleinesLernen: NRT render failed (%)".format(path).warn; ^nil ! n };
		data = FloatArray.newClear(sf.numFrames * sf.numChannels);
		sf.readData(data);
		sf.close;
		frms = data.asArray.clump(mfccSize + 1);
		^n.collect { |i|
			var f = frms.copyRange(i * framesPer, ((i + 1) * framesPer) - 1).drop(1); // as in prCollect
			this.meanDev(f.select { |x| x.last > 0.001 }.collect { |x| x.drop(-1) })
		}
	}

	// ---------------------------------------------------------------- running

	isRunning { ^task.notNil and: { task.isPlaying } }

	start { |delay = 0|
		if (this.isRunning) { "KleinesLernen %: already running".format(name).warn; ^this };
		if (server.serverRunning.not) { "KleinesLernen %: server not running".format(name).warn; ^this };
		task = Task({
			delay.wait;
			this.class.sendSynthDefs(server);
			privBus = Bus.audio(server, 1);
			server.sync;
			group = Group(server.defaultGroup, \addToHead);
			analysisSynth = Synth.tail(group, \kleinesLernenAnalysis, [
				\inBus, inBus, \privBus, privBus.index, \inSelect, 0, \inGain, inGain, \rate, analysisRate
			]);
			this.prMakeOSCdefs;
			server.sync;
			this.post("started (in: %, out: %)".format(inBus, outBus));
			1.wait;
			loop { this.prCycle };
		}).play(SystemClock);
	}

	stop {
		task.stop;
		task = nil;
		oscKeys.do { |key| OSCdef(key).free };
		oscKeys = nil;
		group !? { group.free };
		privBus !? { privBus.free };
		group = analysisSynth = playSynth = privBus = nil;
		autosavePath !? { this.saveTable(autosavePath) };
		this.post("stopped");
	}

	prMakeOSCdefs {
		var mfccKey = "kleinesLernen_mfcc_%".format(name).asSymbol;
		var ampKey = "kleinesLernen_amp_%".format(name).asSymbol;
		oscKeys = [mfccKey, ampKey];
		OSCdef(mfccKey, { |msg|
			if (frames.notNil and: { analysisSynth.notNil and: { msg[1] == analysisSynth.nodeID } }) {
				frames.add(msg.drop(3))
			}
		}, '/kleinesLernen/mfcc', server.addr);
		OSCdef(ampKey, { |msg|
			if (analysisSynth.notNil and: { msg[1] == analysisSynth.nodeID }) {
				this.hearLevel(msg[3])
			}
		}, '/kleinesLernen/amp', server.addr);
	}

	// collect analysis frames for d seconds; returns [means, devs] or nil
	prCollect { |d|
		var collected;
		frames = List.new;
		d.wait;
		collected = frames.asArray;
		frames = nil;
		^this.class.meanDev(collected.drop(1)) // first frame may straddle the switch
	}

	prCycle {
		var listenDur = this.listenDuration, collectDur = this.collectDuration, analysis;

		// listen
		analysisSynth.set(\inSelect, 0);
		this.hear(this.prCollect(collectDur));
		(listenDur + 1 - collectDur).wait;

		// play
		this.decide;
		analysisSynth.set(\inSelect, 1);
		playSynth = Synth.head(group, \kleinesLernenRana, this.synthArgs ++ [\outListen, outBus, \out, privBus.index]);
		analysis = this.prCollect(dur);
		1.wait;
		this.learn(analysis);
	}

	// ---- the steps of a cycle, separate from the timing, so that the live loop
	// above and an offline simulation can run the same logic

	listenDuration { ^(dur * 0.35 * listenJitter).clip(listenRange[0], listenRange[1]) }

	collectDuration { ^this.listenDuration.clip(2, 6) }

	envelopeTimes {
		var attack = (dur/3).min(7), decay = (dur/3).min(7);
		^[attack, (dur - attack - decay).max(3), decay]
	}

	// what the agent heard while listening ([means, devs] or nil)
	hear { |analysis| targetAnalysis = analysis }

	// input level while listening: louder room -> quieter agent
	hearLevel { |amp| playAmp = amp.linlin(0.001, 0.15, 0.3, 0.04) }

	decide {
		currentParameters = this.nextParameters;
		^currentParameters
	}

	// arguments for \kleinesLernenRana for the current decision
	synthArgs {
		var env = this.envelopeTimes;
		^[\attack, env[0], \sustain, env[1], \decay, env[2], \amp, playAmp]
			++ [parNames, currentParameters].flop.flatten
	}

	// analysis of the agent's own sound while playing ([means, devs] or nil)
	learn { |analysis|
		currentAnalysis = analysis;
		// parameter sets whose feedback dies out produce no frames: don't repeat them
		if (currentAnalysis.notNil) { this.addEntry(currentParameters, currentAnalysis); silentCount = 0 } { silentCount = silentCount + 1 };
		if (targetAnalysis.notNil and: { currentAnalysis.notNil }) {
			err = this.compareMFCC(targetAnalysis[0], targetAnalysis[1], currentAnalysis[0], currentAnalysis[1]);
			errHistory = errHistory.add(err).keep(errHistorySize.neg);
		};
		dur = err.linexp(this.currentErrRange[0], this.currentErrRange[1], durRange[1], durRange[0]);
		if (timeJitter > 0) {
			dur = dur * (1 + timeJitter.rand2);
			listenJitter = 1 + timeJitter.rand2;
		};
		this.post("error: %  next dur: %  table: %".format(err.round(0.01), dur.round(0.1), table.size));
		autosavePath !? { this.saveTable(autosavePath) };
	}

	post { |str|
		if (verbose) { "[%] %".format(name, str).postln };
	}

	// ---------------------------------------------------------------- table i/o

	saveTable { |path|
		path = path.standardizePath;
		if (path.endsWith(".scd")) {
			File.use(path, "w", { |f| f.write(table.asCompileString) })
		} {
			table.writeArchive(path)
		}
	}

	loadTable { |path|
		var t;
		path = path.standardizePath;
		if (File.exists(path).not) { "KleinesLernen %: no table at %".format(name, path).warn; ^this };
		// .scd = the old SmallLearning format (compile string)
		t = if (path.endsWith(".scd")) { File.readAllString(path).interpret } { Object.readArchive(path) };
		this.table = t.asArray.select { |e|
			e.size == 3 and: { e[0].size == numParameters and: { e[1].size == mfccSize and: { e[2].size == mfccSize } } }
		};
		this.post("loaded % entries from %".format(table.size, path));
	}
}


// Simulated room so that several agents on one machine can hear each other:
// agents sit on a circle; agent i's microphone (micBuses[i]) receives every
// source (sourceBuses[j]) delayed by distance / 343 m/s, attenuated by 1/distance
// and slightly darkened. Its own speaker is at micDistance.
KleinesLernenRoom {
	var <synth, <distances;

	*new { |sourceBuses, micBuses, radius = 3, micDistance = 0.5, gain = 1, noise = 0, target, server|
		^super.new.init(sourceBuses, micBuses, radius, micDistance, gain, noise, target, server)
	}

	init { |sourceBuses, micBuses, radius, micDistance, gain, noise, target, server|
		var n = sourceBuses.size;
		var positions = n.collect { |i| Polar(radius, 2pi * i / n).asPoint };
		var def;
		server = server ? Server.default;
		sourceBuses = sourceBuses.collect(_.asControlInput);
		micBuses = micBuses.collect(_.asControlInput);
		distances = micBuses.collect { |mb, i|
			sourceBuses.collect { |sb, j| if (i == j) { micDistance } { positions[i].dist(positions[j]).max(micDistance) } }
		};
		def = SynthDef("kleinesLernenRoom%".format(UniqueID.next).asSymbol, { arg gain = 1, noise = 0;
			var srcs = sourceBuses.collect { |b| InFeedback.ar(b) };
			micBuses.do { |mb, i|
				var sig = srcs.collect { |src, j|
					var d = distances[i][j];
					LPF.ar(DelayN.ar(src, d / 343, d / 343), (20000 / d.max(1)).clip(2000, 20000)) / d.max(1)
				}.sum;
				Out.ar(mb, (sig * gain) + (PinkNoise.ar * noise));
			};
		});
		synth = def.play(target ? server.defaultGroup, [\gain, gain, \noise, noise], \addToTail);
	}

	set { |...args| synth.set(*args) }
	free { synth.free }
}
