//This file is part of cbpsc (last revision @ version 0.7).
//
//cbpsc is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
//
//cbpsc is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License along with cbpsc.  If not, see <http://www.gnu.org/licenses/>.
//
// cbpsc : created by Tom Stoll : tms@corpora-sonorus.com : www.corpora-sonorus.com
//
// MetaCorpusDB.sc
// Copyright (C) 2010-2012, Thomas Stoll

CorpusDB : Dictionary {

	var <>sfOffset, <>cuOffset, <>sfgOffset, <>soundFileUnitsMapped;

//*	*new { |name, server| }
// name
// server

	*new { |name, server, verbose=nil|
		^super.new.initCorpus(name, server)
	}

//-	initCorpus { |corpusAnchor, srvr| }
// corpusAnchor	the name of the corpus, or, alternatively, a path to a directory
// srvr			the server attached to this corpus

	initCorpus { |corpusAnchor, srvr, verbose=nil|
		// anchor is an identifier for a corpus (a name, a path, whatever)
		this.add(\anchor -> corpusAnchor.asSymbol);
		this.add(\server -> srvr);
		// the dictionaries that store the data/metadata
		this.add(\sftable -> Dictionary[]);
		this.add(\sftrees -> Dictionary[]);
		this.add(\sfutable -> Dictionary[]);
		this.add(\cutable -> Dictionary[]);

		// mappings hash tables
		this.add(\sfmap -> Dictionary[]);
		this.add(\sfgmap -> Dictionary[]);
		this.add(\transformations -> Dictionary[\0 -> \thru, \thru -> 0, 1 -> \transpose, \transpose -> 1]);
		this.add(\synthdefs -> Dictionary[]);
		
		// keep track of the latest additions to the tables
		this.sfOffset = 0;		this.cuOffset = 0;		this.sfgOffset = 0;
		// dtable = descriptors table; must match to analysis synth; no descriptors for spectral metadata like MFCCs
		this.add(\dtable -> Dictionary[0 -> \unitID, 1 -> \sfgrpID, 2 -> \sfileID, 3 -> \sfRelID,
			4 -> \onset, 5 -> \duration, 6 -> \tRatio, 7 -> \power]);
	
		// send the analysis synth to the server
		this.buildSynths;
		// init the cached flag to false (since there are no units to cache yet)
		this.soundFileUnitsMapped = false;
		^this
	}

//-	buildSynths { }

	buildSynths {
		this[\synthdefs].put( \mfccBufferAnalyzerNRT,
			SynthDef(\mfccBufferAnalyzerNRT, { |srcbufNum, start=0, dur=1, savebufNum, transp=1|
				var env, in, chain, power, mfcc, driver, array;
				env = 	EnvGen.kr(Env.linen(0.01, ((dur / transp) - 0.02), 0.01, 1), gate: 1, doneAction: 2);
				in = 	PlayBuf.ar(1, srcbufNum, BufRateScale.kr(srcbufNum) * transp, startPos: start) * env;
				chain = 	FFT(LocalBuf(2048,1), in);
			
				power =	FFTPower.kr(chain);          // empirical multiplier
				mfcc =	MFCC.kr(chain,24);
				
				// log the metadata into a buffer and signal sclang to read from the buffer
				driver = 	Impulse.kr( 25 );
				Logger.kr(
					[(power * 0.1), mfcc].flatten,
					driver,
					savebufNum
				);
				//Poll.kr(driver, power.ampdb, ":::");
				Out.ar(0, in);
			})
		);
		this[\synthdefs][\mfccBufferAnalyzerNRT].writeDefFile;
		this[\synthdefs].put( \mfccBusAnalyzerNRT,
			SynthDef(\mfccBusAnalyzerNRT, { |inbus=20, dur=1, savebufNum, transp=1|
				var env, in, chain, power, mfcc, driver, array;
				in = In.ar(inbus, 1);
//				env = EnvGen.kr(Env.linen(0.01, ((dur / transp) - 0.02), 0.01, 1), gate: 1, doneAction: 2);
//				in = PlayBuf.ar(1, srcbufNum, BufRateScale.kr(srcbufNum) * transp, startPos: start) * env;
				chain = FFT(LocalBuf(2048,1), in);
			
				power =			FFTPower.kr(chain);          // empirical multiplier
				mfcc =			MFCC.kr(chain,24);
				
				// log the metadata into a buffer and signal sclang to read from the buffer
				driver = Impulse.kr( 25 );
				Logger.kr(
					[(power * 0.1), mfcc].flatten,
					driver,
					savebufNum
				);
				//Poll.kr(driver, power.ampdb, ":::");
				Out.ar(0, in);
			})
		);
		this[\synthdefs][\mfccBusAnalyzerNRT].writeDefFile;
		this[\synthdefs].put( \monoSamplerNRT,
			SynthDef(\monoSamplerNRT, { |outbus=20, srcbufNum, transp=1|
				var env, in, chain;
				env = EnvGen.kr(Env.linen(0.01, ((BufDur.kr(srcbufNum) / transp) - 0.02), 0.01, 1), gate: 1, doneAction: 13);
				in = PlayBuf.ar(1, srcbufNum, BufRateScale.kr(srcbufNum) * transp, startPos: 0) * env;
				Out.ar(outbus, in);
			})
		);
		this[\synthdefs][\monoSamplerNRT].writeDefFile;
		this[\synthdefs].put( \monoSampler,
			SynthDef(\monoSampler, { |outbus=20, srcbufNum, start=0, dur=1, transp=1, attack=0.01, release=0.01|
				var env, in, chain;
				env = EnvGen.kr(Env.linen(attack, ((dur / transp) - (attack+release)), release, 1), gate: 1, doneAction: 13);
				in = PlayBuf.ar(1, srcbufNum, BufRateScale.kr(srcbufNum) * transp, startPos: (start * BufSampleRate.kr(srcbufNum))) * env;
				Out.ar(outbus, in);
			})
		);
		this[\synthdefs][\monoSampler].writeDefFile;
		this[\synthdefs].put( \stereoSamplerNRT,
			SynthDef(\stereoSamplerNRT, { |outbus=20, srcbufNum, transp=1|
				var env, in, chain;
				env = EnvGen.kr(Env.linen(0.01, ((BufDur.kr(srcbufNum) / transp) - 0.02), 0.01, 1), gate: 1, doneAction: 2);
				in = PlayBuf.ar(2, srcbufNum, BufRateScale.kr(srcbufNum) * transp, startPos: 0) * env;
				Out.ar(outbus, in);
			})
		);
		this[\synthdefs][\stereoSamplerNRT].writeDefFile;
		this[\synthdefs].put( \stereoSampler,
			SynthDef(\stereoSampler, { |outbus=20, srcbufNum, start=0, dur=1, transp=1, attack=0.01, release=0.01|
				var env, in, chain;
				env = EnvGen.kr(Env.linen(attack, ((dur / transp) - (attack+release)), release, 1), gate: 1, doneAction: 2);
				in = PlayBuf.ar(2, srcbufNum, BufRateScale.kr(srcbufNum) * transp, startPos: (start * BufSampleRate.kr(srcbufNum))) * env;
				Out.ar(outbus, in);
			})
		);
		this[\synthdefs][\stereoSampler].writeDefFile;
		
		this[\synthdefs].put( \thru,
			SynthDef(\thru, { |outbus=21, inbus=20|
				Out.ar(outbus, In.ar(inbus, 1));
			})
		);
		this[\synthdefs][\thru].writeDefFile;
	}
	
//-	addSynthDef { |symbol, synthdef| }
// symbol		key val
// synthdef	reference to actual SynthDef

	addSynthDef { |symbol, synthdef, verbose=nil|
		this[\synthdefs].put( symbol, synthdef);
		this[\synthdefs][symbol].writeDefFile;
	}

//-	addSoundFile { |path, srcFileID=nil, numChannels=1, sfGrpID=0, tratio=1, synthdefs=nil, params=nil, importFlag=true| }
// path             path ====> key for sftrees Dictionary
// srcFileID=nil    for parent/child relationships as well as importing from XML/JSON
// numChannels=1    2 for stereo
// sfGrpID=0        optional sfile group
// tratio=1         the all important transposition ratio
// importFlag=nil   set to import the soundfile (instead of just the metadata) *** this should be inverted!
// synthdefs=nil    synthdef object name (as a single-item list for child nodes only)
// params=nil       there parameters (as a list - ['param', val, 'param', val, etc...])

	addSoundFile { |path, srcFileID=nil, numChannels=1, sfGrpID=0, tratio=1, synthdefs=nil, params=nil, importFlag=true, verbose=nil|
		var thepath, flag=false, res, prms;
		
		(path != nil).if {
			thepath = PathName.new(path.asString);
		} {
			^nil;
		};
		
		(verbose != nil).if { Post << "Adding Entry:  ================  " << path.asString << " (" << numChannels << " channels).\n"; };
		
		(synthdefs == nil).if {
//			(numChannels == 2).if {
//				synthdefs = [\stereoSamplerNRT, \mfccBusAnalyzerNRT];
//			} {
			synthdefs = [\monoSamplerNRT, \mfccBusAnalyzerNRT];
//			};
			flag = true;
		};
		(verbose != nil).if { Post << "synthdefs: "<< synthdefs << "\n" };
		(params == nil).if {
			params = [[\outbus, 10, \srcbufNum, 0, \tratio, tratio, \dur, 1], [\inbus, 10, \savebufNum, 0, \tratio, tratio]];
		};
				
		(flag == true).if { //no parent tree for this path/file, this is a parent
			
			this[\sftrees].add(thepath.fullPath -> CorpusSoundFileTree.new(this));
			
			(verbose != nil).if { Post << "add new Anchor: " << srcFileID << "\n\n"; };
			res = this[\sftrees][thepath.fullPath].addAnchorSFTree(thepath.fullPath, numChannels, nil, sfGrpID, srcFileID, synthdefs, params, tratio, verbose:verbose);
			((importFlag != nil) && (res != nil)).if { "impoRT!".postln; this.importSoundFileToBuffer(thepath.fullPath, res) };
			// add an sfile unit to the sfile unit table
			this[\sfutable].add(thepath.fullPath -> Dictionary[sfOffset -> Dictionary[\mfccs -> nil, \keys -> nil]]);
			
		} {
			(verbose != nil).if { Post << "add child\n"; };
			res = this[\sftrees][thepath.fullPath].addChildSFTree(srcFileID, numChannels, synthdefs, params, tratio, sfg:sfGrpID, verbose:verbose);
		};		
		// res is the sfile ID, which has been returned from one of the add_____Tree functions
		^res
	}
	
//-	importSoundFileToBuffer { |path, sfid=0| }
// path
// sfid		obligatory, NO DEFAULT

	importSoundFileToBuffer { |path, sfid, verbose=nil|
		Buffer.readChannel(this[\server], path, 0, -1, [0], { |bfrL|
				this[\sftrees][path].tree.add(\bfrL -> bfrL);
		});
		// if stereo, add the right channel
		
//		":::: ".post; sfid.postln; this[\sftrees][path].tree.postln;
		(this[\sftrees][path].tree[\channels] == 2).if
		{
			Buffer.readChannel(this[\server], path, 0, -1, [1], { |bfrR|
				this[\sftrees][path].tree.add(\bfrR -> bfrR);
			});
		};
		^nil
	}

//-	removeSoundFile { |path| }
// path
	removeSoundFile { |path, verbose=nil|
		var thepath;
		thepath = PathName.new(path.asString).fullPath;
		(verbose != nil).if { Post << "Deleting Entry: " << thepath << "   ==============================="; };
		
		(this[\sftable][thepath] != nil).if
		{
			(this[\sftable][thepath][\bfrR] != nil).if { this[\sftable][thepath][\bfrL].free; };
			(this[\sftable][thepath][\bfrR] != nil).if { this[\sftable][thepath][\bfrR].free; };
			this[\sftable][thepath][\abfr].free;
			this[\sftable][thepath].add(\abfr -> nil, \bfrL -> nil, \bfrR -> nil, \uniqueid -> nil, \sfilegroup -> nil, \mfccs -> nil, \keys -> nil, \srcFileID -> nil, \rawmels -> nil);
			this[\sftable].add(thepath -> nil);
			this[\sfgmap].add(thepath -> nil);
		} {
			Post << "WARNING: Something has gone horribly wrong; attempting to remove a non-existant path!\n";
		};
	}

//-	addTransformation { |index, identifier| }
// index -> identifier pairs; builds a mirrored hash table of the available transformations

	addTransformation { |index, identifier, verbose=nil|
		this[\transformations].add(index -> identifier);
		this[\transformations].add(identifier -> index);	}

//-	analyzeSoundFile { |path, group=0, sfid, tratio=1, verbose=nil| }
// path
// group=0
// sfid
// tratio=1

	analyzeSoundFile { |path, group=0, sfid, analyze=true, tratio=1, verbose=nil|
		var fullpath, dir, rmddir, file, pBuf, aBuf, sFile, oscList;
		var timeout = 999, res = 0, thebuffer, ary, timeoffset = 0;
		var currBus = 20;
		var done = 0;
		
		// pathname as a Pathname object; extract dir, file, and full path as Strings
		fullpath = PathName.new(path.asString);
		dir = fullpath.pathOnly.asString;
		file = fullpath.fileNameWithoutExtension.asString;
		fullpath = fullpath.fullPath.asString;

		// execute a command in the terminal .. this will not make a new md dir if it is already there!
		Pipe.new("cd " ++ dir.asString ++ "; mkdir md", "w").close;
		
		rmddir = dir.asString +/+ "md" +/+ file ++ "." ++ tratio ++ ".md.aiff";
		
		sFile = SoundFile.new; sFile.openRead(fullpath); sFile.close;
		
		pBuf = this[\server].bufferAllocator.alloc(1);
		aBuf = this[\server].bufferAllocator.alloc(1);
		
		(verbose != nil).if {
			Post << "RMDDIR: " << rmddir << "\n" << tratio.class << "\n";
			Post << "Dur: " << sFile.duration << "\n" << "allocation pBuf�\n";
			Post << "pairs: " << [pBuf, aBuf] << "\n";
		};
		
		TempoClock.default.tempo = 1;
		oscList = [[0.0, [\b_allocReadChannel, pBuf, fullpath, 0, -1, [0]]]];
		oscList = oscList ++ [[0.01, [\b_alloc, aBuf, ((sFile.duration / 0.04) / tratio).ceil, 25] ]];
		
		(verbose != nil).if { Post << this[\sftrees][path].trackbacks[sfid][1] << "\n"; };
		this[\sftrees][path].trackbacks[sfid][1].do({ |sdef, index|
			var row = this[\sftrees][path].trackbacks[sfid][2][index];
			Post << "row: " << row << "\n";
			row.do({ |val, index|
				
				switch (val,
					\srcbufNum, { row[index+1] = pBuf; },
					\savebufNum, { row[index+1] = aBuf; }, 
					\transp, {
//						Post << "update tratio...\n";
						row[index+1] = tratio;
					}, 
					\outbus, {
						row[index+1] = currBus;
					},
					\inbus, {
						row[index+1] = currBus;
						currBus = currBus + 1;
					},
					\dur, {
						row[index+1] = sFile.duration;
					}
				);
			});
			oscList = oscList ++ [[0.02, ([\s_new, sdef, -1, 1, 0] ++ row).flatten]];
		});
		oscList = oscList ++ [[((sFile.duration / tratio) + 0.03).unbubble, [\b_write, aBuf, rmddir, "wav", "float32"]]];
		// don't free any buffers (yet)
		oscList = oscList ++ [[((sFile.duration / tratio) + 0.04).unbubble, [\c_set, 0, 0]]];
				
		(verbose != nil).if { oscList.postln; }; // or(?): oscList.do({ |item| item.postln });

		(analyze == true).if {
			Score.recordNRT(oscList, "/tmp/analyzeNRT.osc", "/tmp/dummyOut.aiff", options: ServerOptions.new.numOutputBusChannels = 1);
			0.01.wait;
			while({
				Post << "ps -xc | grep 'scsynth'" << "\n";
				res = "ps -xc | grep 'scsynth'".systemCmd; //256 if not running, 0 if running
				((timeout % 10) == 0).if { Post << [timeout, res] << "\n" };
				(res == 0) and: {(timeout = timeout - 1) > 0}
			},{
				0.1.wait;
			});
			0.01.wait;
	//			// clear out the rawmels (to possibly reuse!)
	//			this.clearSoundFileUnits;
			// here's the stupidest line of code that I have ever written!� otherwise the Buffer will not get loaded to the array
			done = 0;
			thebuffer = Buffer.read(this[\server], rmddir, action: { |bfr|
				bfr.loadToFloatArray(action: { |array|
	
					(verbose != nil).if { Post << "Array 1 (rank/size):" << array.rank << ", " << array.size << ", " << array.flatten.sum << "\n"; };
				
					ary = array.clump(25).flop;
					ary[0] = ary[0].ampdb;
					(1..24).do({ |d| ary[d] = ary[d].collect({ |n| n.asStringPrec(4).asFloat }) });
					
					this.addRawMetadata(fullpath, ary.flop);
					
					// why waste the memory?
					this[\sftrees][fullpath].tree[\abfr] = bfr;
					done = 1;
				});
			});
			
			while { done == 0 } { 0.5.wait }; "DONE".postln;
		};
		done = 1;

		aBuf.free; pBuf.free; // "---.md.aiff" saved to disc; free buffers on server
	}

//-	mapIDToSF { |path, sfgrp=0, customMap=nil| }
// path
// sfgrp
// customMap

	mapIDToSF { |path, sfgrp=0, customMap=nil, verbose=nil|
		var mapping;
		(customMap == nil).if
			{
				mapping = this.sfOffset.asInteger;
				this.sfOffset = this.sfOffset + 1;
			} {
				mapping = customMap;
				// custom caller responsible for sfOffset!!!
				//this.sfOffset = mapping.max(this.sfOffset) + 1;
			};
		(verbose != nil).if {
			Post << "mapIDtoSF...\n" << "sfgroup: " << sfgrp << "\n";
			Post << "sfgmap: " << this[\sfgmap] << "\n";
			Post << "mapping: " << mapping << "\n";
		};
		// no check for overwrite... should there be one?
		(this[\sfgmap][sfgrp] == nil).if { this[\sfgmap].add(sfgrp -> Array[])};
		this[\sfgmap][sfgrp] = (this[\sfgmap][sfgrp] ++ mapping).flatten;
		this[\sfmap].add(mapping -> path); // PATH is EITHER a STRING ***OR*** an ARRAY
		this[\sftrees][path].tree.add(\sfilegroup -> sfgrp);
	}

//-	addSoundFileUnit { |path, relid, bounds, cid=nil, sfg=nil, tratio=nil, sfid=nil| }
// path
// relid 
// bounds
// cid=nil
// sfg=nil
// tratio=nil
// sfid=nil

	addSoundFileUnit { |path, relid, bounds, cid=nil, sfg=nil, tratio=1, sfid=nil, verbose=nil|
		var quad;
		(bounds != nil).if
		{
			quad = [cid ? this.cuOffset, sfg ? this.sfgOffset, (sfid ? this[\sfmap].findKeyForValue(path.asString)), relid ];
			
			(cid == nil).if { this.cuOffset = this.cuOffset + 1 };
			
			this[\sfutable][path][\keys] = this[\sfutable][path][\keys] ++ [quad ++ bounds ++ tratio];

			(verbose != nil).if {
				Post << "Adding sound file unit (to sfutable)...mapping: " << (sfid ? this[\sfmap].findKeyForValue(path.asString)) << "\n";
				Post << quad << " ... " << bounds << " ... " << path << "\n";
				Post << "\n" << "KEYS" << this[\sfutable][path][\keys].size << "\n";
				Post << "\n" << "MFCCS" << this[\sfutable][path][\mfccs] << "\n";
			};
			this.soundFileUnitsMapped = false;
			^this[\sfutable][path][\keys].size
		};
	}
	
	// cid, not relid! sfile path/relid pairs no longer operating as indexes...
	// this looks like a bad hack... should be accessing something in this[\sftrees]... ???

//-	updateSoundFileUnit { |path, relid, cid=nil, onset=nil, dur=nil, mfccs=nil, sfg=nil, keypair=nil| }
// path
// relid
// cid=nil
// onset=nil
// dur=nil
// mfccs=nil
// sfg=nil
// keypair=nil

	updateSoundFileUnit { |path, rid=nil, cid=nil, onset=nil, dur=nil, mfccs=nil, sfg=nil, keypair=nil, verbose=nil|
		//var old = this[\sfutable][path][\mfccs][relid], temp, newmfccs, newkeypair;
		var old = this[\sfutable][path][\mfccs][rid], temp, newmfccs, newkeypair;
//		Post << "\nold: " << old << "\nrid: " << rid << "\n";
		temp = [cid ? old[0], sfg ? old[1], old[2], rid ? old[3], onset ? old[4], dur ? old[5], old[6]];
//		Post << "\n" << "temp: " << temp << "\n";
		newmfccs = mfccs ? this[\sfutable][path][\mfccs][rid][7..];
//		Post << "newmfccs: " << newmfccs << "\n";
//		newkeypair = keypair ? [ this[\sfutable][path][\keys][relid][6..] ];
		this[\sfutable][path][\mfccs][rid] = temp ++ newmfccs;
		this[\sfutable][path][\keys][rid] = temp ++ newkeypair;
		
//		Post << this[\sfutable][path][\mfccs][rid] << "\n";
		
		(sfg != nil).if { this[\sftable][path].add(\sfilegroup -> sfg) };
	}

//-	removeSoundFileUnit { |path, relid| }
	removeSoundFileUnit { |path, relid, verbose=nil|
		(relid != nil).if
		{
			this[\sfutable][path][\mfccs].removeAt(relid);
			this[\sfutable][path][\keys].removeAt(relid);
			(relid == this[\sfutable][path][\mfccs].size).if
			{
				^nil
			} {
				(relid..(this[\sfutable][path][\mfccs].size - 1)).do({ |i|
					this[\sfutable][path][\mfccs][i][2] = i;
					this[\sfutable][path][\keys][i][2] = i;
				});
				this.soundFileUnitsMapped = false;
				^(relid..(this[\sfutable][path][\mfcc].size - 1))
			};
		};
	}

	// set the \mfccs tables to nil (empty them) for the provided path
//-	clearSoundFileUnits { |path| }
	clearSoundFileUnits { |path, verbose=nil|
		(verbose != nil).if { Post << "clear soundfileunits!!\n\n\n\n\n"; };
		this[\sfutable][path].add(\rawmels -> nil);
		this[\sfutable][path].add(\mfccs -> nil);
		this[\sfutable][path].add(\keys -> nil);
		this.soundFileUnitsMapped = false;
	}

//-	addRawMetadata { |path, mels=nil| }
	addRawMetadata { |path, mels=nil, verbose=nil|
		(mels != nil).if
		{
			this[\sfutable][path][\rawmels] = mels.flatten.clump(25);
		}
	}

//-	addRawMetadataUnit { |path, mels=nil| }
	addRawMetadataUnit { |path, mels=nil, verbose=nil|
		(mels != nil).if
		{
			(verbose != nil).if { Post << "mels: " << mels << "\n"; };
			this[\sfutable][path][\rawmels] = (this[\sfutable][path][\rawmels] ++ mels).flatten.clump(25);
		}
	}
	
//-	gatherKeys { |path| }
	// raw analysis data -> segmented metadata (desriptors + mfccs)
	gatherKeys { |path, verbose=nil|
		this[\sfutable][path][\keys] = this[\sfutable][path][\keys].flatten.clump(7);
		this[\sfutable][path][\keys].do({ |key7, index|
			(this[\sfutable][path.asString][\mfccs] == nil).if
			{
				this[\sfutable][path.asString][\mfccs] = this[\sfutable][path.asString][\mfccs] ++ [key7];
			} {
				(this[\sfutable][path.asString][\mfccs][index] == nil).if
				{
					this[\sfutable][path.asString][\mfccs] = this[\sfutable][path.asString][\mfccs] ++ [key7];
				};
			};
		});
		(verbose != nil).if { this[\sfutable][path.asString][\mfccs].postln; };
	}

//-	segmentUnits { |path| }
	segmentUnits { |path, verbose=nil|
		var mfccs, tratio;
		
		this.gatherKeys(path);
		
		mfccs = this[\sfutable][path][\rawmels].flop;
		(verbose != nil).if {
			//Post << "Raw mels: " << this[\sfutable][path][\rawmels] << "\n";
//			Post << "segment, raw mels' size: " << this[\sfutable][path][\rawmels].size << "\n\n\n\n";
//			Post << "SIZE: " << this[\sfutable][path][\mfccs].size << "\n";
		};
		
		this[\sfutable][path][\mfccs].do({ |cell, indx|
			var low, high, len, rma = Array[];
			(verbose != nil).if { Post << "====@@@" << [cell, indx] << "\n"; };
			tratio = this[\sfutable][path][\keys][indx][6];
			(verbose != nil).if { Post << "CELL: " << cell << "; tratio: " << tratio << "\n"; };
			low = ((cell[4] / 40) / tratio).floor.asInteger;
			(verbose != nil).if { Post << "low: " << low << "\n"; };
			len = (cell[5] / 40).ceil.asInteger;
			(verbose != nil).if { Post << "Keys: " << this[\sfutable][path][\keys][indx] << "\n"; };
			high = (low + (len / tratio)).asInteger - 1;
			(verbose != nil).if { Post << "len: " << len << " + l/t: " << (len / tratio) << "\n"; };
			mfccs.do({ |row, ix|
				var dezeroed;
				(verbose != nil).if { 
					Post << row.class << " | " << high << "\n\n";
					Post << [low, high, tratio];
				};
				dezeroed = row[low..high];
				dezeroed = dezeroed.reject({|item| (item.isNumber != true) });
				rma = rma.add(dezeroed.mean.asStringPrec(3).asFloat);
			});
			
			this[\sfutable][path][\mfccs][indx] = this[\sfutable][path][\mfccs][indx][0..6].add(rma).flatten;
			this[\sfutable][path][\keys][indx] = this[\sfutable][path][\keys][indx][0..6];
			(verbose != nil).if { 
				Post << "CELL: " << cell << ", index: " << indx << ", rma: " << rma << "\n";
				Post << "\nAFTER:\n" << "KEYS" << this[\sfutable][path][\keys] << "\n\n";
				Post << "\n\n" << "MFCCS: " << this[\sfutable][path][\mfccs].size << "\n\n";
			};
		});
	}
	
//-	addCorpusUnit { |uid, metadata| }
//		add a uid -> metadata mapping to the \cutable (should there be a check to see that uid == metadata[0]?)
	addCorpusUnit { |uid, metadata, verbose=nil|
		this[\cutable].add(uid -> metadata);
		^this[\cutable][uid]
	}

//-	removeCorpusUnit { |uid| }
// 		opposite of add; sets the mapped flag to false (why?)
	removeCorpusUnit { |uid, verbose=nil|
		this[\cutable].removeAt(uid);
		this.soundFileUnitsMapped = false;
		^this[\cutable][uid]
	}

//-	clearCorpusUnits { }
//		dereference them all
	clearCorpusUnits {
		this[\cutable].keysDo({ |cid| this[\cutable].add(cid -> nil) });
	}

//-	getSoundFileUnitMetadata { |sfid, uid| }
	getSoundFileUnitMetadata { |sfid, relid, grpid=0, verbose=nil|
		(this.soundFileUnitsMapped != true).if
		{
			this.mapSoundFileUnitsToCorpusUnits;
		};
		
		^this[\cutable].detect({ |item, i| ((item[1] == grpid) && (item[2] == sfid) && (item[3] == relid)) });
	}

//-	mapSoundFileUnitsToCorpusUnits { |override=false| }
	mapSoundFileUnitsToCorpusUnits { |override=false, verbose=nil|
		((this.soundFileUnitsMapped == false) || (override == true)).if
		{
			this.clearCorpusUnits;
			this[\sfutable].do({ |path|
				(verbose != nil).if { Post << "\n\n\n\npath keys size: " << path[\keys].size << "\n" << path[\keys] << "\n"; };
				path[\keys].do({ |pu, index|
					//[pu[0], (pu ++ path[\mfccs][index][7..]).flatten].postln;
					this.addCorpusUnit(pu[0], (pu ++ path[\mfccs][index][7..]).flatten);
				});
			});
			this.soundFileUnitsMapped = true;
		};
		^this[\cutable]
	}

//-	mapBySFRelID { }
	mapBySFRelID { |verbose=nil|
		var fileMap = Dictionary[];
		var metadata = this.mapSoundFileUnitsToCorpusUnits;
		
		(verbose != nil).if { Post << "metadata: " << metadata << "\n"; };
		
		(metadata.class == Dictionary).if
		{
//			metadata.keys.asArray.sort.postln;
			metadata.keys.asArray.sort.do({ |uid| 
				var filenum = metadata[uid][2];
				(fileMap[filenum] == nil).if
				{
					fileMap.add(filenum -> Dictionary[metadata[uid][3] -> metadata[uid]]);
				} {
					fileMap[filenum].add(metadata[uid][3] -> metadata[uid]);
				};
			});
		} {	
			metadata.sort.do({ |unit| 
				var filenum = unit[1];
				(fileMap[filenum] == nil).if
				{
					fileMap.add(filenum -> Dictionary[unit[3] -> unit]);
				} {
					fileMap[filenum].add(unit[3] -> unit);
				};
			});
		};
		
		(verbose != nil).if { Post << "fileMap: " << fileMap << "\n"; };
		this.add(\sfumap -> fileMap);
		^fileMap
	}

//-	importCorpusFromXML { |server, path| }
//		import & export entire corpora
// server
// path

	importCorpusFromXML { |server, path, verbose=nil|
		var domdoc, tmpDict = Dictionary[], metadataDict = Dictionary[], sfDict;
		var runningCUOffset = this.cuOffset, runningSFOffset = this.sfOffset, runningSFGOffset = this.sfgOffset, thePath, slines, plines;
		
//		Post << "Adding File Entry from XML: " << path << "\n=============\n";
//		Post << "Starting from sf offset: " << runningSFOffset << " + cu Offset: " << runningCUOffset << " + sfg Offset: " << runningSFGOffset << Char.nl;

		// make sure that the XML file exits...
		(File.exists(path.asString) == false).if { ^nil };
		// make sure descriptors are the same...
		domdoc = DOMDocument.new(path.asString);
		domdoc.getDocumentElement.getElementsByTagName("descrid").do({ |tag, index|
			tmpDict.add(tag.getText.asInteger -> tag.getAttribute("name").asSymbol);
		});
		(tmpDict != this[\dtable]).if { "WARNING: Import descriptor list mismatch!".postln; };
		sfDict = Dictionary[];

		// get the anchorPath from XML
		domdoc.getDocumentElement.getElementsByTagName("tree").do({ |entry| // should only be one!
			var theID, theParent;
			thePath = entry.getAttribute("path").asSymbol;
			theID = entry.getAttribute("sfID");
//			Post << theID << "\n";
			theID = theID.asInteger + runningSFOffset;
			theParent = entry.getElementsByTagName("parentFileID")[0].getText.asInteger + runningSFOffset;
	
//			Post << "the path: " << thePath << "\n";

			(sfDict[thePath] == nil).if
			{
				sfDict.add(thePath -> Dictionary[]);
				sfDict[thePath].add(\sfID -> theID);
				sfDict[thePath].add(\parentFileID -> theParent);
				sfDict[thePath].add(\sfileGroup -> (entry.getElementsByTagName("sfileGroup")[0].getText.asInteger + runningSFGOffset)); //add offset
				sfDict[thePath].add(\tratio -> entry.getElementsByTagName("tratio")[0].getText.asFloat);
//				Post << "tratio: " << sfDict[thePath][theID][\tratio] << "\n";
				sfDict[thePath].add(\uniqueID -> entry.getElementsByTagName("uniqueID")[0].getText.asFloat);
				sfDict[thePath].add(\channels -> entry.getElementsByTagName("channels")[0].getText.asString);

//				slines = Dictionary[];
				entry.getElementsByTagName("synthdefs").do({ |synthdef|
					slines = synthdef.getElementsByTagName("sd").collect({ |sd| sd.getText.asSymbol });
				});
				sfDict[thePath].add(\synthdefs -> slines);
				
//				plines = Dictionary[];
				entry.getElementsByTagName("paramslist").do({ |paramslist|

					plines = paramslist.getElementsByTagName("params").collect({ |p|
						p.getText.split($ ).asArray.collect({ |x,i| (i.even).if { x.asSymbol } { x.asFloat } });
					});
				});
//				Post << "plines: " << plines << "\n";

				sfDict[thePath].add(\params -> plines);
//				Post << "sfDict\n" << sfDict << "\n";
				
			} {
//				Post << "ELSE!\n";
				(sfDict[thePath][\children] == nil).if
				{
					sfDict[thePath].add(\children -> Dictionary[]);
//					"children: ".post; sfDict[thePath][theParent][\children].postln;
				};
				(sfDict[thePath][\children][theID] == nil).if
				{
					sfDict[thePath][\children].add(theID -> Dictionary[]);
//					"@ theID: ".post; sfDict[thePath][theParent][\children][theID].postln;
				};
				
				sfDict[thePath][\children][theID].add(\parentFileID -> theParent);
				sfDict[thePath][\children][theID].add(\tratio -> entry.getElementsByTagName("tratio")[0].getText.asFloat);
//				Post << "tratio: " << sfDict[thePath][theParent][\children][theID][\tratio] << "\n";
				
				slines = Dictionary[];
				entry.getElementsByTagName("synthdefs").do({ |synthdef|
					slines.add(theID -> synthdef.getElementsByTagName("sd").collect({ |sd| sd.getText.asSymbol }) );
				});

//				Post << "slines: " << slines << "\n";
				sfDict[thePath][\children][theID].add(\synthdefs -> slines);
				
				plines = Dictionary[];
				entry.getElementsByTagName("paramslist").do({ |paramslist|
						
					plines.add(theID -> paramslist.getElementsByTagName("params").collect({ |p|

						p.getText.split($ ).asArray.collect({ |x,i| (i.even).if { x.asSymbol } { x.asFloat } });
					}));
				});
//				Post << "plines: " << plines[theID].collect({ |pl| pl.collect({ |x| x.class }) }) << "\n";
//				Post << "plines: " << plines << "\n";
				sfDict[thePath][\children][theID].add(\params -> plines);
			};
		});		
//		Post << "sfdict: \n\n" << sfDict << "\n";
		
		domdoc.getDocumentElement.getElementsByTagName("corpusunit").do({ |tag, index|
			var tmpRow = tag.getText.split($ ).asFloat;
			var theGroup = tmpRow[1] + runningSFGOffset;
//			Post << "descr: " << tmpRow[0] << " -> " << tmpRow << Char.nl;
			tmpRow[0] = tmpRow[0] + runningCUOffset;
			tmpRow[2] = tmpRow[2] + runningSFOffset;
			(metadataDict[theGroup] == nil).if
			{
				metadataDict.add((theGroup) -> Dictionary[tmpRow[0] -> tmpRow]);
			} {
				metadataDict[theGroup].add(tmpRow[0] -> tmpRow);
			};				
		});

//		Post << "metadata dict: \n\n" << metadataDict << "\n";

		sfDict.keys.asArray.sort.do({ |pathkey|
			var theID, theGroup;

//			Post << "PK: " << pathkey << "\n" << sfDict[pathkey.asSymbol] << "\n\n\n";

			theID = sfDict[pathkey.asSymbol][\sfID];
			theGroup = sfDict[pathkey.asSymbol][\sfileGroup];
//			Post << sfDict << "\n";
//			Post << "the group: " << theGroup << ", the offset: " << runningSFGOffset << " | " << theID << "\n\n";

//			Post << sfDict[pathkey.asSymbol][\synthdefs] << "\n";

			Post << "number of channels: " << sfDict[pathkey.asSymbol][\channels].asInteger << "\n";
			this.addSoundFile(
				pathkey.asString,
				theID,
				sfDict[pathkey.asSymbol][\channels].asInteger,
				sfGrpID:theGroup,  //add offset
				tratio:sfDict[pathkey.asSymbol][\tratio],
				synthdefs:nil,
				params:nil,
				importFlag:true,
				verbose:false
			);
//			Post << "tratio: " << sfDict[pathkey.asSymbol][\tratio] << "\n";
			this.analyzeSoundFile(pathkey.asString, sfid:theID, analyze:false, tratio:sfDict[pathkey.asSymbol][\tratio]);

			(sfDict[pathkey.asSymbol][\children] != nil).if {
//				Post << "children's keys: " << sfDict[pathkey.asSymbol][\children].keys << "\n";
	
				sfDict[pathkey.asSymbol][\children].keys.asArray.sort.do({ |csfid|
					this.addSoundFile(
						pathkey.asString,
						srcFileID:sfDict[pathkey.asSymbol][\children][csfid][\parentFileID],
						sfGrpID:theGroup,  //add offset
						tratio:sfDict[pathkey.asSymbol][\children][csfid][\tratio],
						synthdefs:sfDict[pathkey.asSymbol][\children][csfid][\synthdefs][csfid][1],
						params:sfDict[pathkey.asSymbol][\children][csfid][\params][csfid][1],
						importFlag: false,
						verbose:false
					);
//					Post << "found tratio: " << sfDict[pathkey.asSymbol][theID][\children][csfid][\tratio] << "\n";
					this.analyzeSoundFile(pathkey.asString, sfid:csfid, analyze:false, tratio:sfDict[pathkey.asSymbol][\children][csfid][\tratio]);
				});
			};
			runningSFOffset = runningSFOffset.max(theID);
			Post << "runningSFOffset after a sfile entry iteration: " << runningSFOffset << Char.nl;
			runningSFGOffset = runningSFGOffset.max(theGroup);
//			Post << "runningSFGOffset after a sfgroup entry iteration: " << runningSFGOffset << Char.nl;

		});
			
//		Post << metadataDict.keys << "\n\n\n";
//		Post << "$$: " << metadataDict[ runningSFGOffset ] << "\n";

//		Post << "\n\n" << metadataDict[  runningSFGOffset ].keys.asArray.sort << "\n\n";
		metadataDict[ runningSFGOffset ].keys.asArray.sort.do({ |cid|
			
			var tmp = metadataDict[ runningSFGOffset ][ cid ], path, last; // cutable row!
//				Post << tmp[0] << " + " << tmp.size << " ";
//				Post << "\n\n\n" << tmp << "\n";
			path = this[\sfmap][tmp[2]].asString;

			//Post << "addSoundFileUnit args: " << [path, tmp[3].asInteger, tmp[4..5], (tmp[0].asInteger + this.cuOffset), (tmp[1] + this.sfgOffset).asInteger, tmp[6].asFloat, tmp[2].asInteger] << "\n";
			last = this.addSoundFileUnit(path, tmp[3].asInteger, tmp[4..5], cid: tmp[0].asInteger, sfg:tmp[1].asInteger, tratio:tmp[6].asFloat, sfid:tmp[2].asInteger) - 1;
//			

//			Post << "gather keys...\n";
			this.gatherKeys(path);
//			Post << this[\sfutable][path][\keys].size << " +=+=+ " << this[\sfutable][path][\mfccs].size << "\n";

		});

		
		metadataDict[ runningSFGOffset ].keys.asArray.sort.do({ |cid|
			
			var tmp = metadataDict[ runningSFGOffset ][ cid ], path, last; // cutable row!
//			Post << "...\n" << cid << ", " << tmp << " ";
			path = this[\sfmap][tmp[2]].asString; // at this point, shouldn't tmp[2] have to have an offset added !!!???
			this.updateSoundFileUnit(
				path,
				tmp[3].asInteger,
				cid: tmp[0].asInteger,
				mfccs: tmp[7..]
			);
		});
		//runningCUOffset = runningCUOffset.max(tmp[0].asInteger + this.cuOffset);
		//this.sfOffset = this.sfOffset + runningSFOffset + 1;
//
		this.mapSoundFileUnitsToCorpusUnits(override:true); // not verbose:true
		
		this.sfgOffset = runningSFGOffset + 1;
		this.sfOffset = runningSFOffset.max(this[\sfmap].keys.maxItem) + 1;
		this.cuOffset = this.cuOffset.max(this[\cutable].keys.maxItem) + 1;
		Post << "After import: " << this.cuOffset << " + " << this.sfOffset << " + " << this.sfgOffset << Char.nl;

	}

//-	exportCorpusToXML { |server, path| }
// server
// path

	exportCorpusToXML { |server, path, verbose=nil|
		File.use(path.asString, "w", { |f|
			f.write("<?xml version=1.0 encoding=utf8 standalone=yes?>\n");
			f.write("<corpusmap name=\"" ++ this[\anchor].asString ++ "\">\n");
			f.write("    <heading name=\"DMAP\">\n");
			this[\dtable].keys.asArray.sort.do({|ky|
				f.write("	        <descrid name=\"" ++ this[\dtable][ky].asString ++ "\">" ++ ky.asString ++ "</descrid>\n");
			});
			f.write("    </heading>\n");
			f.write("    <heading name=\"SFTREES\">\n");
			this[\sftrees].keysValuesDo({ |sfpath, entry|
				var sfile = SoundFile.new;
				
//				Post << sfpath << "\n\n" << entry.tree << "\n";
//				entry.tree.keys.do({ |stindex| // SHOULD ONLY BE ONE KEY!!!
	
					f.write("      <tree path=\"" ++ entry.anchorPath.asString ++ "\" sfID=\"" ++ entry.tree[\sfileID].asString ++ "\">\n");
					f.write("        <parentFileID>" ++ entry.tree[\parentFileID].asString ++ "</parentFileID>\n");
					f.write("        <channels>" ++ entry.tree[\channels].asString ++ "</channels>\n");
					f.write("        <sfileGroup>" ++ entry.tree[\sfileGroup].asString ++ "</sfileGroup>\n");
					f.write("        <tratio>" ++ entry.tree[\tratio].asString ++ "</tratio>\n");
					f.write("        <uniqueID>" ++ entry.tree[\uniqueID].asString ++ "</uniqueID>\n");
					f.write("        <synthdefs>\n");
					entry.tree[\synthdefs].do({ |sd, index|
						f.write("          <sd index=\"" ++ index ++ "\">" ++ sd.asString ++ "</sd>\n");
					});
					f.write("        </synthdefs>\n");
					f.write("        <paramslist>\n");
					entry.tree[\params].do({ |p, index|
						f.write("            <params index=\"" ++ index ++ "\">" ++ p.join($ ).asString ++ "</params>\n");
					});
					f.write("        </paramslist>\n");
					
					entry.tree[\children].keysValuesDo({ |sfid, child|
						
						f.write("        <child sfID=\"" ++ sfid ++ "\">\n");
						f.write("          <parentFileID>" ++ child[\parentFileID] ++ "</parentFileID>\n");
						f.write("          <tratio>" ++ child[\tratio] ++ "</tratio>\n");
						f.write("          <synthdefs>\n");
						child[\synthdefs].do({ |sd, index|
							f.write("            <sd index=\"" ++ index ++ "\">" ++ sd.asString ++ "</sd>\n");
						});
						f.write("          </synthdefs>\n");
						f.write("          <paramslist>\n");
						child[\params].do({ |p, index|
							f.write("            <params index=\"" ++ index ++ "\">" ++ p.join($ ).asString ++ "</params>\n");
						});
						f.write("          </paramslist>\n");
						f.write("        </child>\n");
	
						
					});
					f.write("      </tree>\n");
//				});
			});
			f.write("    </heading>\n");
			f.write("    <heading name=\"UNITS\">\n");
			this.mapSoundFileUnitsToCorpusUnits(true);
			this[\cutable].keys.asArray.sort.do({ |cid|
				var drow = this[\cutable][cid];
				f.write("        <corpusunit sfid=\"" ++ drow[2].asString ++ "\" relid=\"" ++ drow[3].asString ++ "\">" ++ this[\cutable][cid].join($ ).asString ++ "\"</corpusunit>\n");
			});
			f.write("    </heading>\n");
			f.write("</corpusmap>\n");
			
		});
	}
}