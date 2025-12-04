// This work is licensed under GNU GPL v3 or later. See the LICENSE file in the top-level directory.


NPModules {
    classvar <parentDict;
    classvar <all;
    var <moduleDict;
    var <proxyspace;

    *initClass {
        this.registerToAbstractPlayControl;
        all = ();

        parentDict = (
            sine: {|dict|
				// var freq = dict[\freq] ?? {{\freq.kr(440)}};
                var freq = this.krFunc(\freq, \freq.asSpec, dict);
                {
                    SinOsc.ar(freq)
                }
            },
            sinefb: {|dict|
                var freq = this.krFunc(\freq, \freq.asSpec, dict);
				var fb = this.krFunc(\fb, [0, 2], dict);
                {
                    SinOscFB.ar(freq, fb)
                }
            },
            amp: {|dict|
				var amp = this.krFunc(\amp, [0, 1], dict);

                (\filter -> {|in|
                    in * amp
                })
            },
            util: {|dict|
                var ampDb   = this.krFunc(\ampDb, [-24, 24, \lin, 0, 0], dict);
                var distort = this.krFunc(\distort, [0, 10, \lin, 0, 0], dict);
                var lpFreq  = this.krFunc(\lpFreq, \freq.asSpec.default_(20), dict);
                var hpFreq  = this.krFunc(\hpFreq, \freq.asSpec.default_(20000), dict);

				(\filter -> {|in|
                    in = (in * (1+distort)).tanh;
                    in = LPF.ar(HPF.ar(in, hpFreq), lpFreq);
                    in = in * ampDb.dbamp;
                })
            },

            in: {|dict|
                var ins = dict[\ins] ? 0;

				{
                    SoundIn.ar(ins)
                }
            },
            lhpf: {|dict|
				var lpFreq = this.krFunc(\lpFreq, \freq.asSpec.default_(12000), dict);
				var hpFreq = this.krFunc(\hpFreq, \freq.asSpec.default_(20), dict);

                (\filter -> {|in|
                    LPF.ar(HPF.ar(in, hpFreq), lpFreq);
                })
            },
            distort: {|dict|
				var distort = this.krFunc(\distort, [0, 10], dict);

				(\filter -> {|in| (in * (1+distort)).tanh})
            },
            default: {|dict|
                {
                    {nil} // TODO: would be nice to be able to completely remove the module, i.e. np[idx] = nil.
                }
            }
        )
    }

    // helper function to create kr control functions with index suffix
    // to avoid name clashes when multiple instances of the same module are used
    // e.g. \freq -> \freq0, \freq1, ...
    *krFunc {|name, spec, dict, lag|
		var symbol = "%%".format(name, dict.idx).asSymbol;
		^(dict[name.asSymbol] ?? {{ symbol.kr(spec: spec, lag: lag) }});
    }

	krFunc  {|name, spec, dict, lag|
		^this.class.krFunc(name, spec, dict, dict.idx, lag);
	}

	*registerToAbstractPlayControl {
        AbstractPlayControl.proxyControlClasses.put(\module, SynthDefControl);
        AbstractPlayControl.buildMethods.put(\module, #{ | func, proxy, channelOffset = 0, index |
            var role, moduleName, dict, obj;
            var npModules = NPModules.for(proxy); // get NPModules instance for this proxy

			// func can be
            // a Symbol (name of module),
            // a function
            // an association
            // a Dictionary (with at least an entry "\name"),
            case(
                {func.isKindOf(Symbol)}, {
                    dict = (); // empty dict
                    moduleName = func;
                    role = nil; // default role, i.e. function
                },
                {func.isKindOf(Function)}, {
                    dict = (); // empty dict
                    moduleName = func;
                    role = nil; // default role, i.e. function
                },
                {func.isKindOf(Association)}, {
                    dict = ();
                    moduleName = func.value;
                    role = func.key;

					// [role, moduleName.cs].postln
                },
                {// assumed to be a dictionary
                    dict = func; // pass whole dict to module function
                    moduleName = func[\name].value; // required
                    role = func[\role].value; // returns nil if not present
                }
            );

			// add stuff to dictionary
			dict[\idx] = index;
			dict[\proxy] = proxy;
			dict[\channelOffset] = channelOffset;



            moduleName.isNil.if{ Error("AbstractPlayControl: no module name given").throw };
            // if(moduleName.isNil) { moduleName = \default }; // fallback to default module


            moduleName.isKindOf(Symbol).if({
                func = npModules[moduleName]; // get function from npModules
                if(func.isNil) {
                    ("AbstractPlayControl: no module named '%' found, using default module instead".format(moduleName)).warn;
                    role = nil; // ignore role
                    func = npModules[\default] // fallback to default module
                };
                if(role.notNil) {
                    obj = (role -> (func.value(dict, index)));
                } {
                    obj = func.value(dict, index);
                };
            }, {
                // assume moduleName to be something that can be directly buildForProxy
                if(role.notNil, {
                    obj = role -> moduleName; // directly use function
                }, {
                    obj = moduleName; // directly use function
                });
            });
            obj.buildForProxy(proxy, channelOffset, index);
        });

		///////////////////// pmodule is like module but for streams
		AbstractPlayControl.proxyControlClasses.put(\pmodule, StreamControl);
		AbstractPlayControl.buildMethods.put(\pmodule, AbstractPlayControl.buildMethods[\module]);

        ///////////////////// filterWet is like filter but without wet control (all wet)

        AbstractPlayControl.proxyControlClasses.put(\filterWet, SynthDefControl);
        AbstractPlayControl.buildMethods.put(\filterWet, #{ | func, proxy, channelOffset = 0, index |
            var ok, ugen;
            if(proxy.isNeutral) {
                asSynthDef {
                    ugen = func.value(Silent.ar);
                    ok = proxy.initBus(ugen.rate, ugen.numChannels + channelOffset);
                    if(ok.not) { Error("NodeProxy input: wrong rate/numChannels").throw }
                }
            };

            { | out |
                var env;
                if(proxy.rate === 'audio') {
                    env = EnvGate(i_level: 0, doneAction:2, curve:\sin);
					XOut.ar(out, env, SynthDef.wrap(func, nil, [In.ar(out, proxy.numChannels)]))
                } {
                    env = EnvGate(i_level: 0, doneAction:2, curve:\lin);
					XOut.kr(out, env, SynthDef.wrap(func, nil, [In.kr(out, proxy.numChannels)]))
                };
            }.buildForProxy( proxy, channelOffset, index )
        });



    }


    *for {|proxy|
        var npModules;
        // get NPModules instance for this proxy
        proxy.respondsTo(\proxyspace).if({
            // simple case: proxy knows its proxyspace
            npModules = NPModules.all.at(proxy.proxyspace);
            // create NPModules instance for this proxyspace if not existing yet
            npModules.isNil.if({
                npModules = NPModules(proxy.proxyspace);
            });
        }, {
            // TODO: add caching with a dict that maps proxies to their NPModules instance?
            // fallback: search all known ProxySpaces for the given proxy
            var pspace = ProxySpace.all.selectAs({|space| space.envir.includes(proxy)}, Array).first; // first match
            pspace.isNil.if({
                Error("AbstractPlayControl: could not find ProxySpace for given proxy. Did you name your proxyspace?").throw;
            }, {
                // npModules = NPModules.all.at(pspace);
                // // create NPModules instance for this proxyspace if not existing yet
                // npModules.isNil.if({
                // smart constructor: either returns existing instance or creates a new one
                npModules = NPModules(pspace);
                // })
            });
        });
        ^npModules;
    }


    *new {|proxyspace|
        // the default space is the Ndef localhost space
        proxyspace = proxyspace ?? Ndef.dictFor(Server.default);
        this.all[proxyspace].isNil.if({
            ^super.new.init(proxyspace);
        }, {
            ^this.all[proxyspace];
        });
    }

    init{|argProxyspace|
        proxyspace = argProxyspace;

        moduleDict = ();
        moduleDict.parent = this.class.parentDict;
        this.class.all[proxyspace] = this;
    }

    at {|key|
        ^moduleDict[key]
    }

    // borrowed from Modality Toolkit (+Dictionary)
	pr_allKeys {|species|
		var dict = this.moduleDict, ancestry = [];
		while { dict.notNil } {
			ancestry = ancestry.add(dict);
			dict = dict.parent;
		};
		^ancestry.collect { |dict| dict.keys(species) }
	}

    moduleKeys {
        ^this.pr_allKeys(Array).flat.asSet
    }

    put {|key, value, updateNodes = true|
        moduleDict.put(key, value);
        updateNodes.if{
            proxyspace.do{|proxy|
                proxy.objects.keysValuesDo{|idx, synthDefControl|
                    var source = synthDefControl.source;
                    // "source: %".format(source.asCompileString()).postln;
                    source.respondsTo(\key).if{
						switch (source.key,
							\module, {
								var module = source.value;
								// module can be either Symbol, Dictionary, Function.
								// check if module is for the given name
								module.respondsTo(\at).if({ // is a dictionary
									(module[\name] == key).if{
										proxy.put(idx, source); // trigger rebuild
									};
								}, { // assume module to be a symbol or function
									(module == key).if{
										proxy.put(idx, source); // trigger rebuild
									};
								});
							},
							\pmodule, {
								var module = source.value;
								// module can be either Symbol, Dictionary, Function.
								// check if module is for the given name
								module.respondsTo(\at).if({ // is a dictionary
									(module[\name] == key).if{
										proxy.put(idx, source); // trigger rebuild
									};
								}, { // assume module to be a symbol
									(module == key).if{
										proxy.put(idx, source); // trigger rebuild
									};
								});
							}
						);
                    };
                }
            }
        }
    }



}