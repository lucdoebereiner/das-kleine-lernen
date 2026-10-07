// Das kleine Lernen — several agents in parallel on one machine, with recording.
//
// Either evaluate the block below in the IDE / scnvim, or from a shell:
//
//     sclang run_ensemble.scd [numAgents] [recordSeconds] [mode] [micChannels] [speakerChannels]
//     e.g.  sclang run_ensemble.scd 5 1200
//           sclang run_ensemble.scd 4 1200 mics               (mics 1-4 -> speakers 1-4)
//           sclang run_ensemble.scd 4 1200 mics 2,3,4,5 0,1,2,3
//     (channels are counted from 0; recordSeconds 0 = until ~stopAll.())
//
// mode \virtual (default): the agents hear each other through a simulated room
//   (KleinesLernenRoom). Each agent i writes to channel i of a private,
//   contiguous output bus, which is recorded as one multichannel file and
//   monitored as a stereo mix on hardware outs 0/1.
// mode \mics: as in the installation, agent i listens to hardware input
//   ~micChannels[i] and plays on hardware output ~speakerChannels[i].
//   The file contains the N agent signals (as sent to the speakers), followed
//   by the N microphone signals if ~recordMics is true.
//
// ~stopAll.() stops recording and all agents (and quits if run from a shell).

(
var argv = thisProcess.argv;

~numAgents = (argv[0] ? 5).asInteger;
~recordSeconds = argv[1] !? (_.asFloat);       // nil or 0 = record until ~stopAll.()
if (~recordSeconds == 0) { ~recordSeconds = nil };
~mode = (argv[2] ? \virtual).asSymbol;
~fromShell = argv.notEmpty;

~recordDir = "~/recordings/das_kleine_lernen".standardizePath;
~tableDir = "~/recordings/das_kleine_lernen/tables".standardizePath; // learned tables, one per agent
~loadTables = false;          // true: continue learning from the saved tables
~usePretrained = true;        // start agents without a saved table from the pretrained one (pretrain.scd), if it exists
~pretrainedPath = "~/recordings/das_kleine_lernen/pretrained.table".standardizePath;

// virtual room
~roomRadius = 3;              // metres between the circle centre and each agent
~roomGain = 3;                // like a microphone preamp
~roomNoise = 0.0003;          // faint room tone (below the analysis gate on its own)
~monitorAmp = 1;

// \mics mode
~micChannels = argv[3] !? { |a| a.split($,).collect(_.asInteger) } ?? { (0 .. ~numAgents - 1) };
~speakerChannels = argv[4] !? { |a| a.split($,).collect(_.asInteger) } ?? { (0 .. ~numAgents - 1) };
~micGain = 1.5;               // input gain before the analysis (as in the installation); raise for quiet mics
~recordMics = true;           // also record the mic inputs
~device = nil;                // audio device name, nil = system default
if (~mode == \mics and: { ~micChannels.size != ~numAgents or: { ~speakerChannels.size != ~numAgents } }) {
	Error("need % mic and % speaker channels, got % and %".format(~numAgents, ~numAgents, ~micChannels, ~speakerChannels)).throw
};

s = Server.default;
s.options.blockSize = 1;      // essential: the wavefolder feedback loop is one block long
s.options.memSize = 2 ** 16;
s.options.numWireBufs = 256;
s.options.device = ~device;
if (~mode == \mics) {
	s.options.numInputBusChannels = ~micChannels.maxItem + 1;
	s.options.numOutputBusChannels = ~speakerChannels.maxItem + 1;
} {
	s.options.numInputBusChannels = 2;
	s.options.numOutputBusChannels = 2;
};
s.recHeaderFormat = "wav";
s.recSampleFormat = "float";  // no clipping in the file

~stopAll = {
	fork {
		s.stopRecording;
		~agents.do(_.stop);
		~room !? { ~room.free };
		~monitor !? { ~monitor.free };
		~recCopy !? { ~recCopy.free };
		"Das kleine Lernen: stopped. Recording: %".format(~recPath).postln;
		if (~fromShell) { 1.wait; 0.exit };
	}
};

s.waitForBoot {
	var n = ~numAgents, inBuses, outBuses, recChannels;

	File.mkdir(~recordDir);
	File.mkdir(~tableDir);

	if (~mode == \mics) {
		inBuses = ~micChannels.collect { |c| s.options.numOutputBusChannels + c };
		outBuses = ~speakerChannels;
		// gather speaker (and mic) channels into one contiguous bus for the recorder
		recChannels = outBuses ++ (if (~recordMics) { inBuses } { [] });
		~recBus = Bus.audio(s, recChannels.size);
	} {
		~micBus = Bus.audio(s, n);
		~outBus = Bus.audio(s, n);
		inBuses = ~micBus.index + (0 .. n - 1);
		outBuses = ~outBus.index + (0 .. n - 1);
		~recBus = ~outBus;
	};
	~recPath = ~recordDir +/+ "das_kleine_lernen_%_%_%ch.wav".format(Date.localtime.stamp, ~mode, ~recBus.numChannels);
	s.sync;

	if (~mode == \mics) {
		~recCopy = { Out.ar(~recBus, recChannels.collect { |b| InFeedback.ar(b) }) }.play(s, addAction: \addToTail);
	};

	if (~mode != \mics) {
		~room = KleinesLernenRoom(outBuses, inBuses, ~roomRadius, gain: ~roomGain, noise: ~roomNoise);
		~monitor = { Splay.ar(InFeedback.ar(~outBus.index, n)) * \amp.kr(~monitorAmp) }.play(s, addAction: \addToTail);
	};

	~agents = n.collect { |i|
		var agent = KleinesLernen("agent%".format(i + 1), inBuses[i], outBuses[i]);
		var tablePath = ~tableDir +/+ "agent%.table".format(i + 1);
		if (~mode == \mics) { agent.inGain = ~micGain };
		if (~loadTables and: { File.exists(tablePath) }) { agent.loadTable(tablePath) };
		if (agent.table.isEmpty and: { ~usePretrained and: { File.exists(~pretrainedPath) } }) {
			agent.loadTable(~pretrainedPath)
		};
		agent.autosavePath = tablePath;
		agent
	};
	s.sync;

	s.record(~recPath, ~recBus.index, ~recBus.numChannels);
	1.wait;
	// staggered entries, so the agents don't all listen at the same time
	~agents.do { |agent, i| agent.start(i * rrand(2.0, 5.0)) };
	"Das kleine Lernen: % agents, mode %, recording to %".format(n, ~mode, ~recPath).postln;
	if (~mode == \mics) {
		n.do { |i| "  agent%: mic in % -> speaker out %".format(i + 1, ~micChannels[i] + 1, ~speakerChannels[i] + 1).postln };
	};

	~recordSeconds !? { |secs| secs.wait; ~stopAll.() };
};
)

// ---------------------------------------------------------------- while running
// (inside a comment so that running the file from a shell doesn't execute them;
//  evaluate the lines individually)
/*

~stopAll.();

~monitor.set(\amp, 0.5);
~room.set(\noise, 0.0003);
~agents.do(_.verbose_(false));
~agents[0].table.size;
s.meter;
s.plotTree;

// Single agent with explicit buses, e.g. listening to hardware input 0, playing on output 1:
~a = KleinesLernen(\solo, s.options.numOutputBusChannels + 0, 1).start;
~a.stop;
*/
