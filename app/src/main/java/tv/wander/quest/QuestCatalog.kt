package tv.wander.quest

import tv.wander.model.OfflineAnswer
import tv.wander.model.Quest
import java.util.Locale

/** The curated family quests. Each carries an AI rubric plus offline answers so it works without the cloud. */
object QuestCatalog {

    // Facts were checked against published measurements (October 2026). Where a popular number is
    // disputed, the fact says "reported" or quotes the measured figure instead of the myth.

    private val speedSafari = Quest(
        id = "speed-safari",
        title = "Speed Safari",
        prompt = "Find three animals that are faster than a car on the highway (over 100 km/h, 62 mph).",
        goal = 3,
        emoji = "🐆",
        rubric = """
            Goal: a real animal whose top speed is over 100 km/h (62 mph).
            Counts: the page names one specific animal and states a top speed above 100 km/h or 62 mph. Running, swimming, level flight and diving (stooping) speeds all count, so falcons and eagles in a dive qualify. Accept km/h or mph.
            Does not count: animals at or below 100 km/h (pronghorn about 88 km/h, lion about 80, ostrich about 70, racehorse about 70, greyhound about 72), humans, vehicles, machines, fictional or cartoon animals, and debunked myths such as the deer botfly at 1,300 km/h.
            Different species count separately; the same species under another name is a duplicate (Mexican and Brazilian free-tailed bat are one animal).
            The evidence must be a near-verbatim quote from the page that contains the speed.
        """.trimIndent(),
        offline = listOf(
            OfflineAnswer(
                "Peregrine falcon",
                listOf("peregrine falcon", "peregrine falcons", "peregrine", "falco peregrinus"),
                "Peregrine falcons dive at more than 300 km/h, the fastest animal on Earth.",
            ),
            OfflineAnswer(
                "Golden eagle",
                listOf("golden eagle", "golden eagles", "aquila chrysaetos"),
                "Golden eagles can dive at about 240 km/h to catch their prey.",
            ),
            OfflineAnswer(
                "Cheetah",
                listOf("cheetah", "cheetahs", "acinonyx jubatus"),
                "Cheetahs sprint at up to about 110 km/h, but only in short bursts.",
            ),
            OfflineAnswer(
                "Brazilian free-tailed bat",
                listOf("brazilian free-tailed bat", "mexican free-tailed bat", "free-tailed bat", "free-tailed bats", "tadarida brasiliensis"),
                "Brazilian free-tailed bats have been clocked at 160 km/h in level flight.",
            ),
            OfflineAnswer(
                "White-throated needletail",
                listOf("white-throated needletail", "needletail", "spine-tailed swift", "needle-tailed swift", "hirundapus caudacutus"),
                "White-throated needletails are reported to reach 169 km/h in level flight.",
            ),
            OfflineAnswer(
                "Grey-headed albatross",
                listOf("grey-headed albatross", "gray-headed albatross", "thalassarche chrysostoma"),
                "A grey-headed albatross was tracked at 127 km/h riding a storm wind for hours.",
            ),
            OfflineAnswer(
                "Common swift",
                listOf("common swift", "common swifts", "apus apus"),
                "Common swifts have been measured at about 111 km/h in level flight.",
            ),
            OfflineAnswer(
                "Sailfish",
                listOf("sailfish", "sailfishes", "istiophorus"),
                "Sailfish are often listed at about 110 km/h, though scientists still argue about it.",
            ),
        ),
    )

    private val moonCollectors = Quest(
        id = "moon-collectors",
        title = "Moon Collectors",
        prompt = "Find two planets that have more than 20 moons.",
        goal = 2,
        emoji = "🪐",
        rubric = """
            Goal: a planet in our solar system with more than 20 known moons.
            Counts: Jupiter, Saturn or Uranus, when the page gives that planet a moon count above 20. Older pages with smaller counts still count if the number is over 20 (for example "Saturn has 82 moons" or "Uranus has 27 moons"). Today Saturn has over 270 known moons, Jupiter over 90 and Uranus 28 or 29.
            Does not count: Neptune (16 moons), Mars (2), Earth (1), Mercury and Venus (0), dwarf planets such as Pluto (5), exoplanets, and moons themselves.
            The evidence must be a near-verbatim quote from the page that contains the moon count.
        """.trimIndent(),
        offline = listOf(
            OfflineAnswer(
                "Saturn",
                listOf("saturn", "saturn's"),
                "Saturn has over 270 known moons, more than any other planet.",
            ),
            OfflineAnswer(
                "Jupiter",
                listOf("jupiter", "jupiter's"),
                "Jupiter has more than 90 known moons, including Ganymede, the biggest moon of all.",
            ),
            OfflineAnswer(
                "Uranus",
                listOf("uranus", "uranus's", "uranus'"),
                "Uranus has at least 28 known moons, named after characters from Shakespeare and Pope.",
            ),
        ),
    )

    private val deepDive = Quest(
        id = "deep-dive",
        title = "Deep Dive",
        prompt = "Find three creatures that live deeper than 1,000 meters in the ocean.",
        goal = 3,
        emoji = "🐙",
        rubric = """
            Goal: a real ocean creature that lives, hunts or regularly dives deeper than 1,000 meters (3,280 feet), in the midnight zone or deeper.
            Counts: the page names the creature and says it lives or feeds below 1,000 m, gives a depth over 1,000 m, or places it in the midnight (bathypelagic), abyssal or hadal zone or at deep hydrothermal vents. Diving mammals count when their regular hunting dives pass 1,000 m (sperm whale, Cuvier's beaked whale).
            Does not count: creatures that only live shallower than 1,000 m (barreleye fish about 600-800 m, most giant squid sightings unless the page says deeper), "deep-sea" with no depth or zone, submarines, divers, shipwrecks, and fictional monsters.
            Different species count separately. The evidence must be a near-verbatim quote that contains the depth or zone.
        """.trimIndent(),
        offline = listOf(
            OfflineAnswer(
                "Dumbo octopus",
                listOf("dumbo octopus", "dumbo octopuses", "dumbo octopi", "grimpoteuthis"),
                "Dumbo octopuses flap ear-like fins 3,000 to 4,000 m down, some near 7,000 m.",
            ),
            OfflineAnswer(
                "Anglerfish",
                listOf("anglerfish", "angler fish", "anglerfishes", "deep-sea anglerfish", "black seadevil", "ceratioid", "melanocetus"),
                "Many deep-sea anglerfish hunt with a glowing lure 1,000 to 4,000 m below the surface.",
            ),
            OfflineAnswer(
                "Mariana snailfish",
                listOf("mariana snailfish", "snailfish", "snailfishes", "pseudoliparis"),
                "Mariana snailfish live about 7,000 to 8,000 m deep in the Mariana Trench.",
            ),
            OfflineAnswer(
                "Trench amphipod",
                listOf("hirondellea gigas", "hirondellea", "trench amphipod", "hadal amphipod"),
                "Shrimp-like Hirondellea amphipods scuttle almost 11,000 m down in the Challenger Deep.",
            ),
            OfflineAnswer(
                "Giant tube worm",
                listOf("giant tube worm", "giant tubeworm", "tube worm", "tube worms", "tubeworm", "riftia"),
                "Giant tube worms grow more than 2 m long beside hot vents about 2,500 m deep.",
            ),
            OfflineAnswer(
                "Yeti crab",
                listOf("yeti crab", "yeti crabs", "kiwa hirsuta", "kiwa puravida", "kiwa tyleri"),
                "Yeti crabs grow bacteria on their hairy arms near vents about 2,200 m deep.",
            ),
            OfflineAnswer(
                "Sea pig",
                listOf("sea pig", "sea pigs", "scotoplanes"),
                "Sea pigs are sea cucumbers that walk on tube feet across mud more than 1,000 m down.",
            ),
            OfflineAnswer(
                "Bigfin squid",
                listOf("bigfin squid", "big fin squid", "bigfin squids", "magnapinna"),
                "A bigfin squid with long, elbow-bent arms was filmed more than 6,000 m deep.",
            ),
            OfflineAnswer(
                "Cuvier's beaked whale",
                listOf("cuvier's beaked whale", "cuviers beaked whale", "cuvier beaked whale", "goose-beaked whale", "ziphius"),
                "Cuvier's beaked whales have dived almost 3,000 m deep, deeper than any other mammal.",
            ),
            OfflineAnswer(
                "Sperm whale",
                listOf("sperm whale", "sperm whales", "cachalot", "physeter"),
                "Sperm whales dive deeper than 1,000 m, sometimes past 2,000 m, to hunt squid.",
            ),
        ),
    )

    private val timeTravelers = Quest(
        id = "dino-time",
        title = "Time Travelers",
        prompt = "Find two dinosaurs that lived in the Cretaceous period.",
        goal = 2,
        emoji = "🦕",
        rubric = """
            Goal: a real dinosaur that lived in the Cretaceous period, about 145 to 66 million years ago.
            Counts: the page names a specific dinosaur and either says it lived in the Cretaceous (Early, Late or Upper/Lower Cretaceous) or gives a date between 145 and 66 million years ago.
            Does not count: Jurassic or Triassic dinosaurs (Stegosaurus, Brachiosaurus, Allosaurus, Diplodocus, Coelophysis), flying reptiles (Pteranodon, Quetzalcoatlus), sea reptiles (Mosasaurus, plesiosaurs), Dimetrodon, mammoths and other Ice Age animals, modern birds, and toys, films or games.
            Different genera count separately. The evidence must be a near-verbatim quote that mentions the Cretaceous or the date.
        """.trimIndent(),
        offline = listOf(
            OfflineAnswer(
                "Tyrannosaurus rex",
                listOf("tyrannosaurus rex", "tyrannosaurus", "t. rex", "t-rex", "t rex", "trex"),
                "T. rex stomped around 68 to 66 million years ago, at the very end of the Cretaceous.",
            ),
            OfflineAnswer(
                "Triceratops",
                listOf("triceratops"),
                "Triceratops lived about 68 to 66 million years ago, with brow horns about 1 m long.",
            ),
            OfflineAnswer(
                "Velociraptor",
                listOf("velociraptor", "velociraptors"),
                "Velociraptor was about the size of a turkey and lived 75 to 71 million years ago.",
            ),
            OfflineAnswer(
                "Spinosaurus",
                listOf("spinosaurus"),
                "Spinosaurus, a 14 m fish-eating giant, lived around 99 to 93 million years ago.",
            ),
            OfflineAnswer(
                "Ankylosaurus",
                listOf("ankylosaurus"),
                "Ankylosaurus swung a bony tail club about 68 to 66 million years ago.",
            ),
            OfflineAnswer(
                "Argentinosaurus",
                listOf("argentinosaurus"),
                "Argentinosaurus, one of the biggest land animals ever, lived about 95 million years ago.",
            ),
            OfflineAnswer(
                "Iguanodon",
                listOf("iguanodon"),
                "Iguanodon had spiky thumbs and lived about 125 million years ago.",
            ),
            OfflineAnswer(
                "Pachycephalosaurus",
                listOf("pachycephalosaurus"),
                "Pachycephalosaurus had a domed skull up to 25 cm thick, about 68 million years ago.",
            ),
            OfflineAnswer(
                "Parasaurolophus",
                listOf("parasaurolophus"),
                "Parasaurolophus honked through a hollow head crest 76 to 73 million years ago.",
            ),
            OfflineAnswer(
                "Giganotosaurus",
                listOf("giganotosaurus"),
                "Giganotosaurus, a 12 m hunter, prowled South America about 98 million years ago.",
            ),
        ),
    )

    private val tinyTitans = Quest(
        id = "tiny-titans",
        title = "Tiny Titans",
        prompt = "Find three animals that can lift or pull many times their own body weight.",
        goal = 3,
        emoji = "🐜",
        rubric = """
            Goal: a real animal that can lift, carry, pull, push or hold at least 5 times its own body weight.
            Counts: the page names the animal and says it can move or hold a load of 5 or more times its body weight (or gives numbers that show it). Popular figures vary (ants are quoted anywhere from 8 to 50 times); accept the page's number when it is 5 or more.
            Does not count: animals that move less than 5 times their weight (horses, elephants, tigers and eagles carry less), jumping, biting or punching records, machines and robots, and cartoon or fictional creatures.
            Different species count separately; a group and a member of it are one find (ants, then leafcutter ants, is a duplicate unless they are different species).
            The evidence must be a near-verbatim quote that contains the strength claim.
        """.trimIndent(),
        offline = listOf(
            OfflineAnswer(
                "Dung beetle",
                listOf("dung beetle", "dung beetles", "onthophagus taurus", "onthophagus"),
                "One horned dung beetle pulled 1,141 times its own body weight in a lab test.",
            ),
            OfflineAnswer(
                "Oribatid mite",
                listOf("oribatid mite", "oribatid mites", "archegozetes", "moss mite", "beetle mite"),
                "A soil mite smaller than a sesame seed can hold on to 1,180 times its own weight.",
            ),
            OfflineAnswer(
                "Rhinoceros beetle",
                listOf("rhinoceros beetle", "rhinoceros beetles", "rhino beetle", "rhino beetles", "hercules beetle", "atlas beetle"),
                "Rhinoceros beetles can walk while carrying about 30 times their own weight.",
            ),
            OfflineAnswer(
                "Leafcutter ant",
                listOf("leafcutter ant", "leafcutter ants", "leaf-cutter ant", "leaf-cutter ants", "leaf cutter ant", "leaf cutter ants", "atta cephalotes"),
                "Leafcutter ants haul leaf pieces about 8 times their own body weight.",
            ),
            OfflineAnswer(
                "Weaver ant",
                listOf("weaver ant", "weaver ants", "oecophylla", "green ant"),
                "A weaver ant was photographed hanging upside down holding about 100 times its weight.",
            ),
            OfflineAnswer(
                "Coconut crab",
                listOf("coconut crab", "coconut crabs", "robber crab", "birgus latro"),
                "Coconut crabs weigh up to 4 kg but have been reported lifting about 28 kg.",
            ),
        ),
    )

    val all: List<Quest> = listOf(speedSafari, moonCollectors, deepDive, timeTravelers, tinyTitans)

    fun byId(id: String): Quest? = all.firstOrNull { it.id == id }

    /**
     * On-device helper for the offline judge: the first answer of [quest] whose item or keywords appear in
     * [text] as whole words (case-insensitive), skipping answers already in [alreadyFound]. "ant" never
     * matches "elephant" and "saturn" matches "Saturn's".
     */
    fun matchOffline(quest: Quest, text: String, alreadyFound: Collection<String> = emptyList()): OfflineAnswer? {
        if (text.isBlank()) return null
        val haystack = text.lowercase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')
        val taken = alreadyFound.map { QuestEngine.normalize(it) }.toSet()
        for (answer in quest.offline) {
            if (QuestEngine.normalize(answer.item) in taken) continue
            val terms = (answer.keywords + answer.item).map { it.lowercase(Locale.ROOT).trim() }.filter { it.isNotEmpty() }
            if (terms.any { containsWord(haystack, it) }) return answer
        }
        return null
    }

    private fun containsWord(haystack: String, term: String): Boolean {
        var from = 0
        while (from <= haystack.length - term.length) {
            val at = haystack.indexOf(term, from)
            if (at < 0) return false
            val before = if (at == 0) ' ' else haystack[at - 1]
            val afterIndex = at + term.length
            val after = if (afterIndex >= haystack.length) ' ' else haystack[afterIndex]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return true
            from = at + 1
        }
        return false
    }
}
