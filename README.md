# NPModules
*2025, Till Bovermann, tai-studio.org*

A Registry of pre-defined NodeProxy modules for SuperCollider.


## Installation



Install via Quarks:
```sc
Quarks.install("https://github.com/tai-studio/NPModules.git");
```

Once this is in the public Quarks repository, you can install it via:

```sc
Quarks.install("NPModules"); // not yet published
```


## Usage

```sc
Ndef(\a)[0]=  \module -> (name: \sinefb, \freq: {LFNoise0.kr(10).range(50, 74).round(1).midicps!2}); // a stereo sound
Ndef(\a)[2] = \module -> \util; // a utility module with some basic filters
```

Write your own modules by adding it to the registry for the relevant ProxySpace:

```sc
// Add custom module
n = NPModules(); // nil means the default Ndef space
​
n.put(\simpleSound, { |dict| 
    var f = dict[\freq] ? 200; 
    { LFTri.ar(f) } // returns a UGen function
});
```

Then use it in a proxy:

```sc
Ndef(\c, \module -> (name: \simpleSound, freq: 150));.play;
```

Change the sound by redefining the module:

```sc
n.put(\simpleSound, { |dict| 
    var f = dict[\freq] ? 200; 
    { LPF.ar(LFSaw.ar(f), f * 2) } // new definition
}, updateNodes: true); // rebuild any proxies using this module, default is true
```
