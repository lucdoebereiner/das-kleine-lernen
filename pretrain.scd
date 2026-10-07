// Das kleine Lernen — pre-train the agents' memory offline.
//
//     sclang pretrain.scd [numSounds] [path]
//     e.g.  sclang pretrain.scd 3000
//
// Renders numSounds random wavefolder settings faster than real time and analyses
// each one exactly like an agent analyses its own sound while playing (checked:
// offline and live analyses of the same settings differ by ~1, unrelated sounds
// by ~100+). The result is a table that run_ensemble.scd gives to every agent
// at the start (~usePretrained), so the agents don't begin with an empty memory.
// They keep adding their own experience on top of it in the room.

(
var argv = thisProcess.argv;
var n = (argv[0] ? 3000).asInteger;
var path = (argv[1] ? "~/recordings/das_kleine_lernen/pretrained.table").standardizePath;

fork {
	var t0 = Main.elapsedTime, table;
	File.mkdir(path.dirname);
	"pretraining with % sounds ...".format(n).postln;
	table = KleinesLernen.pretrain(n, path, dur: 20, jobs: 16);
	"% entries (% silent settings dropped) written to % in % s".format(table.size, n - table.size, path,
		(Main.elapsedTime - t0).round(1)).postln;
	if (argv.notEmpty) { 0.exit };
};
)
