// Offline tests of the learning code (no server needed):  sclang tests/test_algorithm.scd
(
var a = KleinesLernen(\t), p, q, avg, mfcc, frames, md, pathA, pathS, b;
var check = { |label, cond| "% %".format(if(cond){"PASS"}{"FAIL"}, label).postln };
thisThread.randSeed = 1;
// random parameters within ranges, normalise roundtrip
100.do { p = KleinesLernen.rndParameters;
  q = KleinesLernen.unnormParameters(KleinesLernen.normParameters(p));
  if ((p - q).abs.maxItem > (p.abs * 1e-6 + 1e-9).maxItem) { "roundtrip mismatch % %".format(p,q).postln } };
check.("roundtrip", true);
// geometric averaging of frequencies
avg = KleinesLernen.averageParameters([[2,0.3,100,100,0.1,0.1,0.001],[2,0.3,1000,4000,0.1,0.1,0.01]]);
avg.postln; check.("freq geometric mean", (avg[2] - 316.23).abs < 0.1);
// perturb stays in range
1000.do { q = KleinesLernen.perturbParameters(KleinesLernen.rndParameters, 0.3);
  q.do { |x, i| var r = KleinesLernen.parRanges[i]; if (x < (r[0]*0.9999) or: { x > (r[1]*1.0001) }) { "out of range % %".format(i, x).postln } } };
check.("perturb in range", true);
// knn: empty table -> nil
check.("knn empty", a.knn(3, 0!13, 0!13).isNil);
// learn a synthetic mapping params -> "mfcc" and check knn inverts it
mfcc = { |p| var n = KleinesLernen.normParameters(p); (0..12).collect { |i| (n * (i+1)).sin.sum * 10 } };
300.do { p = KleinesLernen.rndParameters; a.addEntry(p, [mfcc.(p), 0!13]) };
p = KleinesLernen.rndParameters;
q = a.knn(3, mfcc.(p), 0!13);
"target % \n knn    %".format(KleinesLernen.normParameters(p).round(0.01), KleinesLernen.normParameters(q).round(0.01)).postln;
"mfcc err knn: %  random: %".format(a.distanceMFCC(mfcc.(q), mfcc.(p)), a.distanceMFCC(mfcc.(KleinesLernen.rndParameters), mfcc.(p))).postln;
// exact match
check.("exact match", a.knn(3, a.table[5][1], a.table[5][2]) == a.table[5][0]);
// table cap
a.maxTableSize = 100; a.addEntry(p, [0!13, 0!13]); check.("cap", a.table.size == 100);
// meanDev with NaN frames
md = KleinesLernen.meanDev([1!13, 3!13, [0/0] ++ (1!12)]);
check.("meanDev", md[0] == (2!13) and: { md[1] == (1!13) });
check.("meanDev empty", KleinesLernen.meanDev([]).isNil);
// nextParameters with no target
a.verbose = false; check.("next no target", a.nextParameters.size == 7);
// save/load archive + legacy scd
pathA = PathName.tmp +/+ "kleinesLernen_test.table";
pathS = PathName.tmp +/+ "kleinesLernen_test.scd";
a.saveTable(pathA); a.saveTable(pathS);
b = KleinesLernen(\b); b.loadTable(pathA); check.("archive load", (b.table.flat - a.table.flat).abs.maxItem < 1e-6);
b = KleinesLernen(\c); b.loadTable(pathS); check.("scd load", b.table.size == a.table.size);
0.exit;
)
