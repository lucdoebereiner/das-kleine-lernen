SmallLearning {
	var id, channel, <>analysisSynth, <>table;
	
	*initClass {
		StartUp.add {

			SynthDef(\rana, { arg out = 0, outListen = 0, attack, sustain, decay, amp = 0.5, shift = 1.8, delay = 0.0001,
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
			}).add;

		}

	}

	*new { | id, channel |
        ^super.new.init(id, channel)
    }

    init { | idIn, channelIn |
		id = idIn;
		channel = channelIn;
		table = [];
		//		listen = false;
	}

	saveTable { | path |
		var f;
		f = File(path.standardizePath,"w");
		f.write(this.table.asCompileString);
		f.close;
	}

	loadTable { | path |
		var f;
		f = File.readAllString(path.standardizePath);
		table = f.compile.();
	}
	
	collectAnalyses {arg size, dur, callback;
		//		"size ".post; size.postln;
		//		"dur ".post; dur.postln;
		Task({
			var mfccs = [];
			var means = Array.fill(size, {0});
			var devs = Array.fill(size, {0});
			var numFrames = 0;
			
			OSCdef(id, { arg msg;
				var coeffs = msg.drop(3);
				mfccs = mfccs.add(coeffs);
				//				coeffs.postln;
			}, id);
			//			"collect analy".postln;
			dur.wait;
			OSCdef(id).free;
			mfccs = mfccs.drop(1);
			numFrames = mfccs.size;

			//			"mfccs: ".postln;
			//			mfccs.do(_.postln);

			if (mfccs.isEmpty.not) {
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
				
				//				"devs: ".post;
				//				devs.postln;
				

				callback.([means,devs]);
			}
		}).start;
	}

	distance {arg v1, v2;
		^(v1 - v2).squared.sum.sqrt;
	}

	deviatePerc {arg n, perc;
		//	n + (n*perc).bilinrand
		^(n + (n*perc).neg.rrand(n*perc))
	}

	foldParameters {arg parameters;
		var pars = parameters.copy;
		pars[0] = pars[0].fold(1.1, 4);
		pars[1] = pars[1].fold(0.05, 0.7);
		pars[2] = pars[2].fold(150, 6000.0);
		pars[3] = pars[3].fold(150, 6000.0);
		//		pars[4] = pars[4].fold(0.1, 0.9);
		pars[4] = pars[5].fold(0.03, 0.3);
		pars[5] = pars[6].fold(0.03, 0.3);
		pars[6] = pars[7].fold(0.0, 0.1);
		^pars
	}

	rndParameters {
		var pars = Array.fill(7, {0.0});
		pars[0] = exprand(1.1, 4);
		pars[1] = rrand(0.05, 0.7);
		pars[2] = exprand(90, 5000.0);
		pars[3] = exprand(90, 5000.0);
		//		pars[4] = rrand(0.1, 0.9);
		pars[4] = linrrand(0.03, 0.3);
		pars[5] = linrrand(0.03, 0.3);
		pars[6] = exprand(0.00001, 0.1);
		^pars
	}

	deviateParametersPerc {arg pars, perc;
		^this.foldParameters(pars.collect({arg p; this.deviatePerc(p, perc)}))
	}


	deviateParameters {arg pars;
		^this.foldParameters(this.averageParameters([pars, this.rndParameters]))
	}

	averageParameters {arg parameters;
		var nPars = parameters[0].size;
		var pars = Array.fill(nPars, {0.0});
		^nPars.collect({ arg i;
			parameters.collect({arg ps; ps[i]}).mean

		})
	}

	averageCandidates {arg candidates;
		var nPars = candidates[0][1].size;
		var weights = candidates.collect({arg c; c[0]});
		var zeroIdx = weights.indexOf(0.0);
		if (zeroIdx.isNil.not, {
			^candidates[zeroIdx][1]
		}, {
			var scaledWeights = weights.squared.invert.normalizeSum;
			var pars = Array.fill(nPars, {0.0});
			^nPars.collect({ arg i;
				var parsI = candidates.collect({arg c; c[1]}).collect({arg ps; ps[i]});
				(scaledWeights * weights.size * parsI).mean
			})
		})
	}

	
	distanceMFCC {arg v1, v2;
		var n = v1.size;
		^(v1 - v2).squared.collect({arg dist, i;
			//			i.linlin(0, n-1, 1, 0.5) * dist
			dist
		}).sum.sqrt;
	}


	compareMFCC {arg means1, devs1, means2, devs2;
		^(this.distanceMFCC(means1, means2) + (this.distanceMFCC(devs1, devs2) * 0.1))
	}


	knn {arg n, means, devs;
		var candidates = table.keep(n).collect({arg entry;
			var distance = this.compareMFCC(entry[1],entry[2],means,devs);
			var parameters = entry[0];
			[distance, parameters]
		});
		// "knn means input: ".post; means.postln;
		// "knn devs input: ".post; devs.postln;
		// "knn n: ".post; n.postln;
		// "first candidates: ".post; candidates.postln;
		candidates = candidates.sort({arg x,y; x[0] > y[0]});
		//		"first candidates sorted: ".post; candidates.postln;
		table.drop(n).do({ arg entry, i;
			var distance = this.compareMFCC(entry[1],entry[2],means,devs);
			var parameters = entry[0];
			if (distance < candidates[0][0]) {
				//		"adding candidate from table at index:".post; i.postln;
				candidates[0] = [distance, parameters];
				candidates = candidates.sort({arg x,y; x[0] > y[0]});
			};
		});
		//		"candidates: ".post;
		//		candidates.postln;
		//~averageParameters.(candidates.collect({arg c; c[1]}))
		^this.averageCandidates(candidates)
	}


	
	start {
		var mfccSize = 13;
		var targetAnalysis;
		var currentAnalysis;
		var currentParameters = this.rndParameters();
		// var makeParameterTableEntry = { arg meansDevs;
		// 	table = table.add([ currentParameters, meansDevs[0], meansDevs[1] ] );
		// };
		var listenFunc = { arg dur, analysisSynth;
			analysisSynth.set(\inSelect, 0);
			"listen fun".postln;
			this.collectAnalyses(mfccSize, dur, { arg meansDevs;
				targetAnalysis = meansDevs;
			})
		};
		var playFunc = { arg bus, dur, meansDevs, analysisSynth, amp, lastErr;
			var attack = (dur/3).min(7);
			var decay = (dur/3).min(7);
			var sustain = (dur - attack - decay).max(3);
			//			currentParameters = this.nextParameters.(table, meansDevs[0], meansDevs[1]);
			//			if (listen) {
			currentParameters = this.knn(3, meansDevs[0], meansDevs[1]);
			if (lastErr > 45) {
				"error over 45, new possibility".postln;
				currentParameters = this.rndParameters;
			};
			if (0.08.coin) {
				"coin, new possibility".postln;
				currentParameters = this.rndParameters;
			};

			// "knn means ".post; meansDevs[0].postln;
				// "knn devs ".post; meansDevs[1].postln;
				// "knn parameters ".post;
				//				currentParameters.postln;
			//			} {
			//	currentParameters = this.rndParameters;
			//			};
			//	"current parameters".post; currentParameters.postln;
			analysisSynth.set(\inSelect, 1);
			Synth.before(analysisSynth, \rana, [
				\attack, attack,
				\sustain, sustain,
				\decay, decay,
				\shift, currentParameters[0],
				\foldThresh, currentParameters[1],
				\freq1, currentParameters[2],
				\freq2, currentParameters[3],
				//				\mix, currentParameters[4],
				\width1, currentParameters[4],
				\width2, currentParameters[5],
				\delay, currentParameters[6],
				\amp, amp,
				\outListen, channel,
				\out, bus]);
			this.collectAnalyses(mfccSize, dur, { arg meansDevs;
				currentAnalysis = meansDevs;
				//"adding current analysis: ".post; currentAnalysis.postln;
				table = table.add([currentParameters, currentAnalysis[0], currentAnalysis[1]]);
			})
		};
		
		var mainTask = Task({

			var bus = Bus.audio(Server.default, 1);
			var dur = 8; // todo
			var playAmp = 0.2;
			var err = 40.0;

			//			\inSelect, 0]);
			analysisSynth = //Synth(\analysis, [\freq, 3, \inBus, bus.index, \inSelect, 1]);
			{ arg freq = 3, inSelect = 1, testFreq = 300;
				var in, fft, mfcc, amp, flatness, synthIn = In.ar(bus), audioIn, ampthresh;
				
				audioIn = SoundIn.ar(channel) * 1.5;
				//audioIn = SinOsc.ar(testFreq);
				audioIn = LPF.ar(audioIn, 3000);
				audioIn = Compander.ar(audioIn, audioIn, 0.2, 1, 0.3) * 2;
				
				in = SelectX.ar(inSelect, [audioIn, synthIn]);
				//				amp = Amplitude.kr(HPF.ar(LeakDC.ar(in),120)).poll;
				amp = Amplitude.kr(in);
				
				mfcc = FluidMFCC.kr(in, 13, startCoeff: 1); // drop first band
				//				ampthresh = ((amp > 0.05) * inSelect) + (inSelect-1).abs;
				SendReply.kr(Impulse.kr(freq) * (amp > 0.001), id, mfcc, 1);
				SendReply.kr(Impulse.kr(1) * (1-inSelect), '/amp' ++ channel, amp.lag2ud(0.1,5), 2);
			}.play;
			
			analysisSynth.postln;

			OSCdef('/amp' ++ channel, { arg msg;
				var amp = msg[3];
				"mic amp while listening :".post;  amp.postln;
				playAmp = amp.linlin(0.001, 0.15, 0.3, 0.04);
			}, '/amp' ++ channel);

			
			//			OSCdef(\amp, { arg msg;
			//	var amp = msg[3];
				//				"mic amp while listening :".post;  amp.postln;
			//	playAmp = amp.linlin(0.01, 0.2, 0.25, 0.65) * err.linexp(0.4,1.6,0.6,1);
			//			}, '/amp');
			1.wait;
			inf.do({
				var listenDur;
				// dur.postln;
				listenDur = (dur*0.35).clip(3,15);
				// 
				//				"playing".postln;
				listenFunc.(listenDur.clip(2,6), analysisSynth);
				(listenDur + 1).wait;
				
				//				"starting play".postln;
				playFunc.(bus, dur, targetAnalysis, analysisSynth, playAmp, err);
				(dur + 1).wait;

				// targetAnalysis[0].postln;
				// targetAnalysis[1].postln;
				// currentAnalysis[0].postln;
				// currentAnalysis[1].postln;
				if (targetAnalysis.isNil.not && currentAnalysis.isNil.not) {
					"Error: ".post;
					err = this.compareMFCC(targetAnalysis[0], targetAnalysis[1], currentAnalysis[0], currentAnalysis[1]);
					err.postln;
				};
				
				dur = err.linexp(10,90,90,20);
				//		"Table Size: ".post;
				//				table.size.postln;
			});
		});
		mainTask.start;
		
	}
	
}

