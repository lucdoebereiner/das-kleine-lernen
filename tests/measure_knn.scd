// How well does the agent's inverse model work on the real synth?
// Everything is rendered offline (NRT), so this takes a few minutes.
//
//     sclang tests/measure_knn.scd
//
// 1. Smoothness: how much does the sound change for small parameter steps,
//    relative to the distance between two unrelated sounds?
// 2. Inverse model: for held-out target sounds, choose parameters with
//    different strategies, render them and measure how close the result is
//    to the target (the agent imitating a sound it could in principle make).

fork {
	var nTrain = 2000, nTest = 200, sizes = [100, 500, 2000];
	var t0 = Main.elapsedTime, rnd = KleinesLernen;
	var trainPars, trainAna, train, testPars, testAna, test, evalAgent, scaledD, rawD, pairs, refScaled, refRaw;
	var bases, steps = [0.005, 0.02, 0.05, 0.1], pertPars, pertAna;
	var strategies, jobs, preds, predAna, results, median, report;

	median = { |xs| var s = xs.copy.sort; if (s.isEmpty) { 0/0 } { s[s.size div: 2] } };
	thisThread.randSeed = 2026;

	// ---- data
	trainPars = nTrain.collect { rnd.rndParameters };
	testPars = nTest.collect { rnd.rndParameters };
	#trainAna, testAna = (rnd.renderAnalyses(trainPars ++ testPars, 20, 16)).clumps([nTrain, nTest]);
	train = [trainPars, trainAna].flop.select { |e| e[1].notNil }.collect { |e| [e[0], e[1][0], e[1][1]] };
	test = [testPars, testAna].flop.select { |e| e[1].notNil };
	"rendered % sounds in % s; silent: train %/%, test %/%".format(nTrain + nTest, (Main.elapsedTime - t0).round(1),
		nTrain - train.size, nTrain, nTest - test.size, nTest).postln;

	// evaluation distances: scaled by the spread over the full training set, and raw (= the agents' error)
	evalAgent = KleinesLernen(\eval).verbose_(false);
	evalAgent.table = train;
	scaledD = { |x, y| evalAgent.searchDistance(x[0], x[1], y[0], y[1]) };
	rawD = { |x, y| evalAgent.compareMFCC(x[0], x[1], y[0], y[1]) };
	pairs = 500.collect { [train.choose, train.choose] }.collect { |p| [[p[0][1], p[0][2]], [p[1][1], p[1][2]]] };
	refScaled = median.(pairs.collect { |p| scaledD.(*p) });
	refRaw = median.(pairs.collect { |p| rawD.(*p) });
	"unrelated sounds, median distance: scaled %  raw %".format(refScaled.round(0.01), refRaw.round(0.1)).postln;
	"spread per coefficient (means): %".format(evalAgent.featureScale[0].round(0.1)).postln;

	// ---- 1. smoothness
	bases = train.scramble.keep(100);
	pertPars = steps.collect { |sigma| bases.collect { |b| rnd.perturbParameters(b[0], sigma) } };
	pertAna = rnd.renderAnalyses(pertPars.flatten(1), 20, 16).clump(bases.size);
	"\n1. SMOOTHNESS: parameter step (std dev, normalised) -> sound change relative to unrelated sounds".postln;
	steps.do { |sigma, si|
		var d = bases.collect { |b, bi|
			var a = pertAna[si][bi];
			a !? { scaledD.([b[1], b[2]], a) / refScaled }
		}.select(_.notNil);
		"  step %: median % | 75th pct % | silent %".format(sigma, median.(d).round(0.01),
			d.copy.sort[(d.size * 0.75).asInteger].round(0.01), bases.size - d.size).postln;
	};

	// ---- 2. inverse model
	strategies = [
		[\raw_k1, false, 1], [\raw_k3, false, 3],              // original distance
		[\scaled_k1, true, 1], [\scaled_k3, true, 3], [\scaled_k5, true, 5]
	];
	jobs = List.new;   // [size, strategy, testIndex, parameters]
	sizes.do { |size|
		var agent = KleinesLernen(\m).verbose_(false);
		agent.table = train.keep(size);
		strategies.do { |st|
			agent.normalizeFeatures = st[1];
			test.do { |t, ti| jobs.add([size, st[0], ti, agent.knn(st[2], t[1][0], t[1][1])]) };
		};
	};
	test.do { |t, ti| jobs.add([0, \random, ti, rnd.rndParameters]) };
	predAna = rnd.renderAnalyses(jobs.collect(_[3]), 20, 16);

	"\n2. INVERSE MODEL: achieved distance to the target, relative to unrelated sounds (lower = better)".postln;
	"   median scaled | median raw | silent results".postln;
	report = { |size, name|
		var sel = jobs.collect { |j, ji| [j, predAna[ji]] }.select { |x| x[0][0] == size and: { x[0][1] == name } };
		var ok = sel.select { |x| x[1].notNil };
		var ds = ok.collect { |x| scaledD.(test[x[0][2]][1], x[1]) / refScaled };
		var dr = ok.collect { |x| rawD.(test[x[0][2]][1], x[1]) / refRaw };
		"  table %  %  % | % | %".format(size.asString.padLeft(4), name.asString.padRight(10),
			median.(ds).round(0.001), median.(dr).round(0.001), sel.size - ok.size).postln;
	};
	sizes.do { |size| strategies.do { |st| report.(size, st[0]) } };
	report.(0, \random);
	"\ntotal time % s".format((Main.elapsedTime - t0).round(1)).postln;
	0.exit;
};
