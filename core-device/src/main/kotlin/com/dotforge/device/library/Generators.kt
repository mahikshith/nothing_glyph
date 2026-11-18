package com.dotforge.device.library

import com.dotforge.device.DeviceProfile
import com.dotforge.device.AnimationLibrary as Presets

/** Effect generators: classic algorithms (plasma, fire, life, ...) written for the round 25x25 mask. */
internal object Generators {
    private const val SK = "Effect idea from Glyph by Spandan Kumar (MIT); code written for DotForge."
    private const val FUNKY = "Noise-cloud idea from FunkyClouds by Stefan Petrick (MIT); code written for DotForge."
    private val C = Categories

    private fun g(id: String, name: String, cat: Category, tags: String, credit: String? = null, make: (DeviceProfile) -> com.dotforge.device.Animation) =
        LibraryItem("gen-$id", name, cat, tags.split(' '), credit, ItemKind.GENERATOR, make)

    val items: List<LibraryItem> = listOf(
        // the twelve DotForge presets
        g("heartbeat", "Heartbeat", C.LoveEmoji, "heart love pulse beat", null, Presets::heartbeat),
        g("ripple", "Ripple", C.Abstract, "ripple rings water", null, Presets::ripple),
        g("rain", "Rain", C.NatureWeather, "rain weather drops storm", null, Presets::rain),
        g("spiral", "Spiral", C.Abstract, "spiral arm spin", null, Presets::spiral),
        g("radar", "Radar sweep", C.Alerts, "radar sweep scan", null, Presets::radar),
        g("loading", "Loading arc", C.Loaders, "loading spinner arc wait", null, Presets::loading),
        g("starfield", "Starfield", C.NatureWeather, "stars night sky twinkle", null, Presets::starfield),
        g("fireworks", "Fireworks burst", C.Seasonal, "fireworks celebration party", null, Presets::fireworks),
        g("bounce", "Bouncing ball", C.Games, "ball bounce", null, Presets::bounce),
        g("wave", "Rolling wave", C.NatureWeather, "wave sea water", null, Presets::wave),
        g("orbit", "Two moons", C.Abstract, "orbit moons planet", null, Presets::orbit),
        g("fillup", "Fill up", C.Loaders, "fill glass level progress", null, Presets::fillUp),
        // field effects
        g("plasma", "Plasma", C.Abstract, "plasma sines retro", SK, GenField::plasma),
        g("noise-clouds", "Noise clouds", C.Abstract, "noise clouds smooth fog", FUNKY, GenField::noiseClouds),
        g("swirl", "Swirl", C.Abstract, "swirl twist vortex", SK, GenField::swirl),
        g("kaleidoscope", "Kaleidoscope", C.Abstract, "kaleidoscope mirror symmetry", SK, GenField::kaleido),
        g("rings", "Pulsing rings", C.Abstract, "rings circles concentric", SK, GenField::rings),
        g("silk", "Silk", C.Abstract, "silk flow stripes", SK, GenField::silk),
        g("wash", "Light wash", C.Abstract, "wash gradient sweep", SK, GenField::wash),
        g("metaballs", "Metaballs", C.Abstract, "metaballs lava blobs", SK, GenField::metaballs),
        g("tunnel", "Tunnel zoom", C.Abstract, "tunnel zoom squares", SK, GenField::tunnel),
        g("voronoi", "Voronoi cells", C.Abstract, "voronoi cells borders", SK, GenField::voronoi),
        g("interference", "Interference", C.Abstract, "interference moire rings", SK, GenField::interference),
        g("lissajous", "Lissajous trace", C.Abstract, "lissajous curve trail", null, GenField::lissajous),
        g("black-hole", "Black hole", C.Abstract, "black hole space spiral", SK, GenField::blackHole),
        g("galaxy", "Spiral galaxy", C.Abstract, "galaxy space spiral arms", SK, GenField::galaxy),
        g("lattice", "Lattice", C.Abstract, "lattice grid weave", SK, GenField::lattice),
        g("caustics", "Caustics", C.Abstract, "caustics water light pool", SK, GenField::caustics),
        g("op-art", "Op art", C.Abstract, "op art moire stripes", SK, GenField::opArt),
        g("disco", "Disco floor", C.Music, "disco party dance flash", SK, GenField::disco),
        g("pendulum-wave", "Pendulum wave", C.Abstract, "pendulum swing wave", SK, GenField::pendulum),
        g("spirograph", "Spirograph", C.Abstract, "spirograph curve draw", SK, GenField::spirograph),
        g("comets", "Comets", C.Abstract, "comets orbit tails", SK, GenField::comets),
        g("wireframe", "Wireframe cube", C.Abstract, "cube wireframe 3d rotate", SK, GenField::wireframe),
        g("glitch", "Glitch static", C.Abstract, "glitch static tear", SK, GenField::glitch),
        // simulations and particles
        g("fire", "Fire", C.NatureWeather, "fire flames heat campfire", SK, GenSim::fire),
        g("matrix-rain", "Matrix rain", C.Abstract, "matrix rain code falling", null, GenSim::matrixRain),
        g("life", "Game of Life", C.Games, "life cellular automaton conway", null, GenSim::life),
        g("sand", "Falling sand", C.Abstract, "sand grains pile", SK, GenSim::sand),
        g("boids", "Boids flock", C.Abstract, "boids flock birds swarm", SK, GenSim::boids),
        g("ant", "Langton's ant", C.Games, "ant langton automaton", SK, GenSim::ant),
        g("reaction", "Reaction-diffusion", C.Abstract, "reaction diffusion spots organic", SK, GenSim::reaction),
        g("flow-field", "Flow field", C.Abstract, "flow field particles noise", SK, GenSim::flowField),
        g("maze", "Maze builder", C.Games, "maze labyrinth carve", SK, GenSim::maze),
        g("balls", "Bouncing balls", C.Games, "balls bounce physics", SK, GenSim::balls),
        g("dna-helix", "DNA helix", C.Abstract, "dna helix strands double", SK, GenSim::helix),
        g("snow", "Snow", C.NatureWeather, "snow flakes winter weather", SK, GenSim::snow),
        g("window-rain", "Window rain", C.NatureWeather, "rain window drops slide", SK, GenSim::windowRain),
        g("lightning", "Lightning", C.NatureWeather, "lightning storm thunder bolt", SK, GenSim::lightning),
        g("meteors", "Meteors", C.NatureWeather, "meteors shooting stars night", SK, GenSim::meteors),
        g("candle", "Candle", C.NatureWeather, "candle flame flicker", SK, GenSim::candle),
        g("magma", "Magma", C.Abstract, "magma lava lamp blobs", SK, GenSim::magma),
        g("cloud-drift", "Cloud drift", C.NatureWeather, "clouds sky weather drift", SK, GenSim::cloudDrift),
        g("ocean-waves", "Ocean waves", C.NatureWeather, "ocean waves sea water", SK, GenSim::waves),
        g("aurora", "Aurora", C.NatureWeather, "aurora northern lights sky", SK, GenSim::aurora),
        g("fireflies", "Fireflies", C.NatureWeather, "fireflies glow night summer", SK, GenSim::fireflies),
        g("bubbles", "Bubbles", C.NatureWeather, "bubbles soap water rise", SK, GenSim::bubbles),
        g("confetti", "Confetti", C.Seasonal, "confetti party celebration", SK, GenSim::confetti),
        g("floaters", "Floaters", C.Abstract, "floaters blobs soft drift", SK, GenSim::floaters),
        g("equalizer", "Equaliser bars", C.Music, "equalizer equaliser bars spectrum music", SK, GenSim::equalizer),
        g("scope", "Waveform scope", C.Music, "scope oscilloscope waveform audio", SK, GenSim::scope),
        g("spinner", "Orbit spinner", C.Loaders, "spinner orbit dots loading", null, GenSim::spinner),
        // small pattern effects
        g("checkerboard", "Checkerboard flip", C.Abstract, "checkerboard squares flip", null, GenSim::checkerboard),
        g("centre-expand", "Expand and collapse", C.Abstract, "expand centre disc grow", null, GenSim::centreExpand),
        g("implode", "Implode", C.Abstract, "implode rings shrink", null, GenSim::implode),
        g("fan", "Fan blades", C.Abstract, "fan blades spin", null, GenSim::fan),
        g("waterfall", "Waterfall", C.NatureWeather, "waterfall streaks falling", null, GenSim::waterfall),
        g("outer-ring", "Rim chase", C.Loaders, "rim ring edge chase", null, GenSim::outerRing),
        g("cross-blink", "Cross blink", C.Alerts, "cross blink plus x", null, GenSim::crossBlink),
        g("loading-bar", "Loading bar", C.Loaders, "loading bar progress", null, GenSim::loadingBar),
        g("target", "Target", C.Abstract, "target bullseye rings", null, GenSim::target),
    )
}
