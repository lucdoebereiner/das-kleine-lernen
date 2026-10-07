Server.default.options.blockSize = 1;
Server.default.options.numInputBusChannels = 6;
Server.default.options.numOutputBusChannels = 6;

s.reboot;
s.volume.gui;

(
~sl1 = SmallLearning('/1', 2);
~sl1.loadTable("~/tmp/table2.scd");
~sl1.start;

~sl2 = SmallLearning('/2', 3);
~sl2.loadTable("~/tmp/table2.scd");
~sl2.start;

~sl3 = SmallLearning('/3', 4);
~sl3.loadTable("~/tmp/table2.scd");
~sl3.start;

~sl4 = SmallLearning('/4', 5);
~sl4.loadTable("~/tmp/table2.scd");
~sl4.start;

)


~sl.saveTable("~/tmp/table2.scd");

'/amp' ++ 2



s.scope



(
var f, g;
f = File("~/tmp/table1.scd".standardizePath,"w");
f.write(~sl.table.asCompileString);
f.close;
)

(
var f, g;
f = File.readAllString("~/tmp/table1.scd".standardizePath);
f.compile.().postln;
)


~sl.table.do(_.postln)

[].isEmpty

~sl.knn(3, ~sl.table[0][1], ~sl.table[0][2])

~sl.table.size

~sl.analysisSynth.set(\testFreq, 200)

~sl.listen = true
~sl.listen
s.meter


x = { Out.ar(, SinOsc.ar(210) * 0.1) }.play

x.free

~compare.(~sl.table[4][1], ~sl.table[4][2], ~sl.table[6][1], ~sl.table[6][2])

~sl.averageCandidates( [ [ 0.17046310830199, [ 1.1675971189974, 0.4143025457859, 1743.7855011876, 3495.0175441796, 0.41309385299683, 0.083513932228088, 0.058778697252274 ] ], [ 0.4548998590188, [ 1.2234009981235, 0.46722829937935, 25.8150095247, 5838.0500723785, 0.22219886779785, 0.037038545608521, 0.22040054440498 ] ], [ 0.1, [ 1.5191586256305, 0.66346618533134, 156.73221654473, 553.69605314714, 0.1985652923584, 0.057261006832123, 0.15757164359093 ] ] ])

[0.0,3].indexOf(0.0)

1000.squared

([ 0.45284553522567, 0.54715446477433, 1.0 ] * 3 * [ 3.4967807506398, 2.2948577534561, 3.3876075771339 ])

[21, 25, 0].collect({arg e; e-[21, 25, 0].maxItem })

[21, 25, 1].invert.normalizeSum


[2,3,4].invert

normalizeSum

[0,0,0].normalizeSum

(
~sl.table.size.do({arg i;
	i.postln;
	~sl.compareMFCC(~sl.table[0][1], ~sl.table[0][2], ~sl.table[i][1], ~sl.table[i][2]).postln;
})
)


~sl.compareMFCC(~sl.table[0][1], ~sl.table[0][2], ~sl.table[4][1], ~sl.table[4][2])


~sl.compareMFCC(~sl.table[0][1], ~sl.table[0][2], ~sl.table[4][1], ~sl.table[4][2])



~sl.table[0][1]

~sl.table[4][1]

[[1,10], [3, 434], [2, 21]].sort({arg x,y; x[0] > y[0]});

~a = [1,2,3,4]



~a.keep(2)


~a

~a.drop(3)

~a

(1-[1, 6, 0].normalizeSum)

([1, 6, 1].normalizeSum * 3 * [0.5, 0.9, -0.1]).mean

0433

(
SynthDef(\rana, { arg out = 0, attack, sustain, decay, amp = 0.5, shift = 1.8,
		foldThresh = 0.31, freq1 = 1321, freq2 = 343, width1 = 0.2, width2 = 0.2, mix = 0.5;
		var in = LocalIn.ar(1);
		var rm = (in*in).lag2(0.2);
		var env = EnvGen.ar(Env([0,1,1,0],[attack, sustain, decay], [6,1,-6]), doneAction: 2);
		var fold = ((in* shift + ((rm/0.05).clip(0,1).linlin(0,1,0.4,0.01).lag2(4)) ).fold(foldThresh.neg, foldThresh)*(1/foldThresh)); 
		var fold1 = Resonz.ar(fold, freq1, width1);
		var fold2 = Resonz.ar(fold, freq2, width2);
		fold = SelectX.ar(mix, [fold1, fold2]);
		fold = fold * (0.85 + (env*0.2));
		LocalOut.ar(fold);
		fold = HPF.ar(fold, 100);
		Out.ar(out, SelectX.ar( (rm < 0.001), [fold * 0.5, SinOsc.ar(100) * 0.4]));
		Out.ar(0, LPF.ar(fold * env * amp, 4000) * -10.dbamp);
}).store;


SynthDef(\analysis, { arg freq = 5, inBus = 0, inSelect = 0;
	var in, fft, mfcc, amp, flatness, synthIn = In.ar(inBus), audioIn;
	
	audioIn = SoundIn.ar([0,1]).sum * 20;
	audioIn = Compander.ar(audioIn, audioIn, 0.1, 1, 0.3) * 2;
	
	in = SelectX.ar(inSelect, [audioIn, synthIn]);
	amp = Amplitude.kr(in);
	fft = FFT(LocalBuf(2048), in, 1);
	
	mfcc = MFCC.kr(fft, 13).drop(1); // drop first band
	SendReply.kr(Impulse.kr(freq) * (amp > 0.001), '/mfcc', mfcc, 1);
	SendReply.kr(Impulse.kr(1) * (1-inSelect), '/amp', amp.lag(3), 2);
	Out.ar(0, DC.ar(0));
}).store;

~rndParameters =  {
	var pars = Array.fill(7, {0.0});
	pars[0] = exprand(1.1, 4);
	pars[1] = rrand(0.1, 0.7);
	pars[2] = exprand(150, 6000.0);
	pars[3] = exprand(150, 6000.0);
	pars[4] = rrand(0.1, 0.9);
	pars[5] = linrrand(0.03, 0.3);
	pars[6] = linrrand(0.03, 0.3);
	pars
};

)




~bus = Bus.audio(s, 1);

~analysis = Synth(\analysis, [\freq, 3, \inBus, ~bus.index, \inSelect, 1]);
~analysis.free
(
~pars = ~rndParameters.();

Synth.before(~analysis, \rana, [
	\attack, 1,
	\sustain, 4,
	\decay, 1,
	\shift, ~pars[0],
	\foldThresh, ~pars[1],
	\freq1, ~pars[2],
	\freq2, ~pars[3],
	\mix, ~pars[4],
	\width1, ~pars[5],
	\width2, ~pars[6],
	\amp, 0.3,
	\out, ~bus.index]);

~collectAnalyses.(12, 4, {arg result; result.postln});
)

(
OSCdef(\mfcc, { arg msg;
	var coeffs = msg.drop(3);
	//mfccs = mfccs.add(coeffs);
	//	coeffs.postln;
	~coeffs = coeffs;
}, '/mfcc');
)

~coeffs.plot

(
~collectAnalyses = {arg size, dur, callback;
	Task({
		var mfccs = [];
		var means = Array.fill(size, {0});
		var devs = Array.fill(size, {0});
		var numFrames = 0;
		
		OSCdef(\mfcc, { arg msg;
			var coeffs = msg.drop(3);
			mfccs = mfccs.add(coeffs);
			//			coeffs.postln;
		}, '/mfcc');
		dur.wait;
		OSCdef(\mfcc).free;
		numFrames = mfccs.size;
				
		// calculate means
		mfccs.do({ arg coeffs;
			coeffs.do({ arg c, i;
				means[i] = means[i] + c;
			});
		});
		means.do({ arg c, i;
			means[i] = c / numFrames;
		});
		
		// calculate std devs
		mfccs.do({ arg coeffs;
			coeffs.do({ arg c, i;
				devs[i] = devs[i] + (c - means[i]).squared;
			});
		});
		devs.do({ arg d, i;
			devs[i] = (d / numFrames).sqrt;
		});
		
		callback.([means,devs]);
	}).start;
};
)