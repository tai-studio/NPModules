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
                var freq = dict[\freq] ?? {{\freq.kr(440)}};
                {
                    SinOsc.ar(freq)
                }
            },
            sinefb: {|dict|
                var freq = dict[\freq] ?? {{\freq.kr(440)}};
                var fb = dict[\fb] ?? {{\fb.kr(0, spec: [0, 2])}};
                {
                    SinOscFB.ar(freq, fb)
                }
            },
            amp: {|dict|
                var amp = dict[\amp] ? 0.1;
                (\filter -> {|in|
                    in * amp
                })
            },
            util: {|dict, idx|
                var ampDb   = this.krFunc(\ampDb, [-24, 24, \lin, 0, 0], dict, idx);
                var distort = this.krFunc(\distort, [0, 10, \lin, 0, 0], dict, idx);
                var lpFreq  = this.krFunc(\lpFreq, \freq.asSpec.default_(20), dict, idx);
                var hpFreq  = this.krFunc(\hpFreq, \freq.asSpec.default_(20000), dict, idx);
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
                var lpFreq = dict[\lpFreq] ? 12000;
                var hpFreq = dict[\hpFreq] ? 20;
                    
                (\filter -> {|in| 
                    LPF.ar(HPF.ar(in, hpFreq), lpFreq);
                })
            },
            distort: {|dict, idx|
                var distort = dict[\distort] ? 0;
                (\filter -> {|in| (in * (1+distort)).tanh})
            },
            default: {|dict|
                {
                    {nil} // TODO: would be nice to be able to completely remvove the module, i.e. np[idx] = nil.
                }
            }
        )
    }

    // helper function to create kr control functions with index suffix
    // to avoid name clashes when multiple instances of the same module are used
    // e.g. \freq -> \freq0, \freq1, ...
    *krFunc {|name, spec, dict, idx, lag|
        ^(dict[name] ?? {{ "%%".format(name, idx).asSymbol.kr(spec: spec, lag: lag) }});
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

                    [role, moduleName.cs].postln
                },
                {// assumed to be a dictionary
                    dict = func; // pass whole dict to module function
                    moduleName = func[\name].value; // required
                    role = func[\role].value; // returns nil if not present
                }
            );

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
                        (source.key == \module).if {
                            var module = source.value;

                            // "module: %".format(module).postln;
                            // "idx: % (%)".format(idx, idx.class).postln;
                            // "proxy: %".format(proxy.asCompileString()).postln;

                            // module can be either a Symbol or a Dictionary
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
                        };
                    };
                }
            }
        }
    }



}