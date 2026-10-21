package tv.wander.ai

import tv.wander.model.OfflineAnswer
import tv.wander.model.Quest

/**
 * "Lumo's pick" without the cloud: the household's recent page titles pick a topic, and the topic picks a
 * quest that the on-device judge can still check (every template ships its own offline answers).
 */
internal object OfflineQuests {
    class Template(
        val key: String,
        val topicWords: List<String>,
        val title: String,
        val prompt: String,
        val goal: Int,
        val emoji: String,
        val rubric: String,
        val answers: List<OfflineAnswer>,
    ) {
        fun toQuest(): Quest = Quest(
            id = "lumo-" + TextKit.hash8(key),
            title = title,
            prompt = prompt,
            goal = goal,
            emoji = emoji,
            rubric = rubric,
            offline = answers,
            generated = true,
        )
    }

    private fun a(item: String, fact: String, vararg keywords: String) =
        OfflineAnswer(item, if (keywords.isEmpty()) listOf(item.lowercase()) else keywords.toList(), fact)

    val RECORD_BREAKERS = Template(
        key = "record-breakers",
        topicWords = emptyList(),
        title = "Record Breakers",
        prompt = "Find two animals that hold a world record, like fastest, tallest or biggest.",
        goal = 2,
        emoji = "🏆",
        rubric = "Counts: an animal the page says is the fastest, tallest, largest, heaviest or longest-lived of all " +
            "animals or of its group (blue whale, cheetah, giraffe). The page must state the record.",
        answers = listOf(
            a("Blue whale", "the biggest animal that has ever lived.", "blue whale"),
            a("Cheetah", "the fastest animal on land."),
            a("Giraffe", "the tallest animal on Earth."),
            a("Peregrine falcon", "it dives at over 300 km/h.", "peregrine falcon", "peregrine"),
            a("Ostrich", "the biggest bird, with the biggest eggs."),
            a("Greenland shark", "it can live for hundreds of years.", "greenland shark"),
            a("Sailfish", "one of the fastest fish in the sea."),
            a("African elephant", "the biggest animal on land.", "african elephant"),
        ),
    )

    val TEMPLATES: List<Template> = listOf(
        Template(
            key = "ring-spotters",
            topicWords = listOf("space", "planet", "saturn", "jupiter", "mars", "venus", "mercury", "neptune", "uranus",
                "moon", "star", "galaxy", "nasa", "rocket", "astronaut", "comet", "asteroid", "orbit", "solar",
                "telescope", "nebula", "universe", "eclipse", "cosmos"),
            title = "Ring Spotters",
            prompt = "Find two planets in our solar system that have rings.",
            goal = 2,
            emoji = "🔭",
            rubric = "Counts: a planet the page says has rings: Saturn, Jupiter, Uranus or Neptune. Moons, dwarf " +
                "planets and planets around other stars do not count.",
            answers = listOf(
                a("Saturn", "its bright rings are made of ice and rock."),
                a("Jupiter", "it has thin, dusty rings that are hard to see."),
                a("Uranus", "its rings circle the planet on its side."),
                a("Neptune", "it has five thin, dark rings."),
            ),
        ),
        Template(
            key = "glow-hunters",
            topicWords = listOf("ocean", "sea", "fish", "shark", "whale", "dolphin", "octopus", "squid", "coral",
                "reef", "jellyfish", "marine", "seahorse", "crab", "plankton", "deep sea", "underwater"),
            title = "Glow Hunters",
            prompt = "Find three sea creatures that can make their own light.",
            goal = 3,
            emoji = "🔦",
            rubric = "Counts: a sea creature the page says is bioluminescent or makes its own light (anglerfish, " +
                "lanternfish, firefly squid). Creatures that only reflect light do not count.",
            answers = listOf(
                a("Anglerfish", "it dangles a glowing lure to catch food.", "anglerfish", "angler fish"),
                a("Lanternfish", "rows of glowing spots line its body.", "lanternfish"),
                a("Firefly squid", "it lights up the sea near Japan each spring.", "firefly squid"),
                a("Vampire squid", "it squirts glowing slime to confuse predators.", "vampire squid"),
                a("Comb jelly", "many glow, and their combs shimmer like rainbows.", "comb jelly", "ctenophore"),
                a("Crystal jelly", "its glow helped scientists discover glowing proteins.", "crystal jelly", "aequorea"),
                a("Hatchetfish", "lights on its belly hide its shadow.", "hatchetfish"),
                a("Dinoflagellate", "these tiny plankton make waves sparkle blue.", "dinoflagellate"),
            ),
        ),
        Template(
            key = "plant-munchers",
            topicWords = listOf("dinosaur", "jurassic", "cretaceous", "triassic", "fossil", "tyrannosaurus", "rex",
                "raptor", "velociraptor", "triceratops", "stegosaurus", "paleontology", "paleontologist", "extinct"),
            title = "Plant Munchers",
            prompt = "Find two dinosaurs that ate only plants.",
            goal = 2,
            emoji = "🦕",
            rubric = "Counts: a dinosaur the page says was a herbivore or ate plants (triceratops, stegosaurus, " +
                "brachiosaurus). Meat-eaters and flying or swimming reptiles do not count.",
            answers = listOf(
                a("Triceratops", "it munched plants with its parrot-like beak."),
                a("Stegosaurus", "this plant-eater had plates along its back."),
                a("Brachiosaurus", "its long neck reached leaves high in the trees."),
                a("Diplodocus", "one of the longest dinosaurs ever, and it ate plants."),
                a("Ankylosaurus", "this armored dinosaur ate low plants."),
                a("Iguanodon", "it had thumb spikes and ate plants."),
                a("Parasaurolophus", "its head crest may have made trumpet sounds."),
                a("Apatosaurus", "this giant plant-eater weighed as much as several elephants.", "apatosaurus", "brontosaurus"),
            ),
        ),
        Template(
            key = "team-builders",
            topicWords = listOf("insect", "bug", "ant", "bee", "beetle", "butterfly", "moth", "spider", "termite",
                "wasp", "caterpillar", "ladybug", "grasshopper", "dragonfly", "hive"),
            title = "Team Builders",
            prompt = "Find two insects that live and build together in big colonies.",
            goal = 2,
            emoji = "🐜",
            rubric = "Counts: an insect the page says lives in colonies or hives that build nests together (ants, " +
                "termites, honey bees, paper wasps). Insects that live alone do not count.",
            answers = listOf(
                a("Honey bee", "tens of thousands can share one hive.", "honey bee", "honeybee"),
                a("Termite", "some build mounds taller than a grown-up."),
                a("Ant", "some ant colonies have millions of workers."),
                a("Paper wasp", "it builds nests from chewed-up wood.", "paper wasp"),
                a("Bumblebee", "they live in small colonies with a queen.", "bumblebee", "bumble bee"),
            ),
        ),
        Template(
            key = "grounded-birds",
            topicWords = listOf("bird", "eagle", "falcon", "owl", "penguin", "parrot", "ostrich", "flamingo",
                "hummingbird", "feather", "nest", "wings", "emu"),
            title = "Grounded Birds",
            prompt = "Find two birds that cannot fly.",
            goal = 2,
            emoji = "🐧",
            rubric = "Counts: a bird the page says cannot fly or is flightless (penguin, ostrich, emu, kiwi). Birds " +
                "that fly only a little do not count.",
            answers = listOf(
                a("Penguin", "it swims instead of flying."),
                a("Ostrich", "the biggest bird on Earth runs instead of flying."),
                a("Emu", "this tall Australian bird runs instead of flying."),
                a("Kiwi", "this New Zealand bird has tiny wings and can't fly."),
                a("Cassowary", "it has a helmet-like crest and can't fly."),
                a("Kakapo", "the world's heaviest parrot can't fly."),
                a("Rhea", "this South American bird runs on long legs."),
                a("Dodo", "it lived on Mauritius and couldn't fly."),
            ),
        ),
        Template(
            key = "big-cat-count",
            topicWords = listOf("animal", "mammal", "lion", "tiger", "elephant", "giraffe", "cheetah", "bear", "wolf",
                "zebra", "safari", "wildlife", "monkey", "gorilla", "kangaroo", "zoo", "leopard", "jaguar"),
            title = "Big Cat Count",
            prompt = "Find three different kinds of big wild cats.",
            goal = 3,
            emoji = "🐯",
            rubric = "Counts: a large wild cat species (lion, tiger, leopard, jaguar, snow leopard, cheetah, cougar, " +
                "clouded leopard). House cats and small wild cats do not count.",
            answers = listOf(
                a("Lion", "lions live in family groups called prides."),
                a("Tiger", "the tiger is the biggest wild cat."),
                a("Snow leopard", "its thick tail keeps it warm in the mountains.", "snow leopard"),
                a("Clouded leopard", "it has very long fangs for its size.", "clouded leopard"),
                a("Leopard", "leopards often carry their meals up into trees."),
                a("Jaguar", "jaguars have a super strong bite."),
                a("Cheetah", "the fastest land animal."),
                a("Cougar", "it's also called a puma or mountain lion.", "cougar", "puma", "mountain lion"),
            ),
        ),
        Template(
            key = "wild-weather",
            topicWords = listOf("weather", "storm", "hurricane", "tornado", "rain", "snow", "cloud", "climate",
                "lightning", "volcano", "earthquake", "flood", "wind", "thunder"),
            title = "Wild Weather",
            prompt = "Find three kinds of extreme weather.",
            goal = 3,
            emoji = "⛈",
            rubric = "Counts: an extreme weather event the page describes (tornado, hurricane, blizzard, " +
                "thunderstorm, hailstorm, drought, heatwave, sandstorm). Ordinary rain or clouds do not count.",
            answers = listOf(
                a("Tornado", "a spinning column of air that touches the ground."),
                a("Hurricane", "a giant spinning storm that forms over warm oceans.", "hurricane", "typhoon", "cyclone"),
                a("Blizzard", "a snowstorm with strong winds."),
                a("Thunderstorm", "a storm with lightning and thunder.", "thunderstorm"),
                a("Hailstorm", "balls of ice fall from the sky.", "hailstorm", "hail"),
                a("Drought", "a long time with too little rain."),
                a("Heatwave", "days of unusually hot weather.", "heatwave", "heat wave"),
                a("Sandstorm", "strong winds lift sand into giant clouds.", "sandstorm", "dust storm"),
            ),
        ),
        Template(
            key = "body-bosses",
            topicWords = listOf("body", "heart", "brain", "bone", "skeleton", "muscle", "blood", "lung", "skin",
                "organ", "health", "teeth", "tooth", "anatomy", "cell", "digestion"),
            title = "Body Bosses",
            prompt = "Find three organs inside the human body.",
            goal = 3,
            emoji = "🧠",
            rubric = "Counts: a human organ the page describes (heart, brain, lungs, liver, stomach, kidneys, skin, " +
                "intestines, pancreas). Bones, muscles and cells on their own do not count.",
            answers = listOf(
                a("Heart", "it beats about 100,000 times a day."),
                a("Brain", "it runs everything your body does."),
                a("Lungs", "they bring oxygen into your blood.", "lung"),
                a("Liver", "it cleans your blood and helps digest food."),
                a("Stomach", "it mixes food with acid to break it down."),
                a("Kidneys", "they filter waste out of your blood.", "kidney"),
                a("Skin", "your skin is your biggest organ."),
                a("Intestines", "they soak up the good stuff from food.", "intestine"),
                a("Pancreas", "it helps control the sugar in your blood."),
            ),
        ),
        Template(
            key = "wonder-seekers",
            topicWords = listOf("history", "ancient", "egypt", "pyramid", "rome", "roman", "greek", "greece", "castle",
                "king", "queen", "pharaoh", "medieval", "empire", "viking", "museum", "archaeology"),
            title = "Wonder Seekers",
            prompt = "Find two of the Seven Wonders of the Ancient World.",
            goal = 2,
            emoji = "🏛",
            rubric = "Counts: one of the Seven Wonders of the Ancient World (Great Pyramid of Giza, Hanging Gardens " +
                "of Babylon, Colossus of Rhodes, Lighthouse of Alexandria, Temple of Artemis, Statue of Zeus, " +
                "Mausoleum at Halicarnassus). Modern wonders do not count.",
            answers = listOf(
                a("Great Pyramid of Giza", "the only ancient wonder still standing.", "great pyramid", "pyramid of giza"),
                a("Hanging Gardens of Babylon", "no one knows for sure where they were.", "hanging gardens"),
                a("Colossus of Rhodes", "a giant bronze statue of the sun god.", "colossus of rhodes", "colossus"),
                a("Lighthouse of Alexandria", "one of the tallest buildings of its time.", "lighthouse of alexandria", "pharos"),
                a("Temple of Artemis", "a huge marble temple in Ephesus.", "temple of artemis"),
                a("Statue of Zeus", "a giant seated statue of ivory and gold.", "statue of zeus"),
                a("Mausoleum at Halicarnassus", "a tomb so grand it gave us the word mausoleum.", "halicarnassus", "mausoleum"),
            ),
        ),
        Template(
            key = "secret-fruits",
            topicWords = listOf("food", "fruit", "vegetable", "plant", "flower", "tree", "garden", "cooking", "recipe",
                "tomato", "apple", "farm", "seed", "leaf", "kitchen"),
            title = "Secret Fruits",
            prompt = "Find two foods that scientists call fruits but we cook like vegetables.",
            goal = 2,
            emoji = "🍅",
            rubric = "Counts: a food the page says is botanically a fruit (it grows from a flower and holds seeds) but " +
                "is usually eaten as a vegetable: tomato, cucumber, pumpkin, avocado, eggplant, pepper, zucchini, olive.",
            answers = listOf(
                a("Tomato", "it grows from a flower and holds seeds, so it's a fruit."),
                a("Cucumber", "botanists call it a fruit."),
                a("Pumpkin", "a giant fruit full of seeds."),
                a("Avocado", "it's a big berry with one seed."),
                a("Eggplant", "it's a berry, botanically speaking.", "eggplant", "aubergine"),
                a("Bell pepper", "it has seeds inside, so it's a fruit.", "bell pepper", "capsicum"),
                a("Zucchini", "it grows from a flower, so it's a fruit.", "zucchini", "courgette"),
                a("Olive", "olives are fruits that grow on trees."),
            ),
        ),
        Template(
            key = "orchestra-hunt",
            topicWords = listOf("music", "song", "band", "orchestra", "piano", "guitar", "violin", "drum", "singer",
                "instrument", "composer", "symphony", "concert", "opera"),
            title = "Orchestra Hunt",
            prompt = "Find three instruments you can hear in an orchestra.",
            goal = 3,
            emoji = "🎻",
            rubric = "Counts: an instrument that is part of a symphony orchestra (violin, cello, flute, clarinet, " +
                "trumpet, timpani, harp and more). Electric guitars and synthesizers do not count.",
            answers = listOf(
                a("Violin", "the smallest, highest string instrument."),
                a("Cello", "it's played sitting down, between the knees."),
                a("Double bass", "the biggest, lowest string instrument.", "double bass"),
                a("Flute", "it's played by blowing across a hole."),
                a("Clarinet", "a woodwind with a single reed."),
                a("Oboe", "the orchestra tunes to its note."),
                a("Bassoon", "a long woodwind with a deep voice."),
                a("Trumpet", "the highest brass instrument."),
                a("Trombone", "it has a slide to change notes."),
                a("French horn", "its tube is coiled into a circle.", "french horn"),
                a("Tuba", "the biggest, lowest brass instrument."),
                a("Timpani", "big kettle drums that can be tuned."),
                a("Harp", "a concert harp has about 47 strings."),
            ),
        ),
        Template(
            key = "olympic-ball-games",
            topicWords = listOf("sport", "football", "soccer", "basketball", "tennis", "olympic", "baseball",
                "swimming", "athlete", "team", "cricket", "rugby", "hockey", "championship", "league"),
            title = "Ball Games",
            prompt = "Find three Olympic sports that are played with a ball.",
            goal = 3,
            emoji = "⚽",
            rubric = "Counts: a sport at the Summer Olympics played with a ball (football, basketball, volleyball, " +
                "handball, tennis, table tennis, water polo, field hockey, golf, rugby sevens).",
            answers = listOf(
                a("Football", "the most popular sport in the world.", "football", "soccer"),
                a("Basketball", "it was invented in 1891 with peach baskets."),
                a("Volleyball", "players hit the ball over a high net."),
                a("Handball", "teams throw the ball into a goal."),
                a("Table tennis", "it's also called ping-pong.", "table tennis", "ping pong"),
                a("Tennis", "players hit the ball over a net with rackets."),
                a("Water polo", "like handball, but in a pool.", "water polo"),
                a("Field hockey", "played with sticks and a small hard ball.", "field hockey", "hockey"),
                a("Golf", "players try to sink the ball in as few shots as possible."),
                a("Rugby sevens", "rugby with seven players on each side.", "rugby sevens", "rugby"),
            ),
        ),
        Template(
            key = "inventors-club",
            topicWords = listOf("computer", "robot", "internet", "invention", "inventor", "machine", "engine",
                "electricity", "technology", "train", "airplane", "science", "engineer", "vehicle"),
            title = "Inventors' Club",
            prompt = "Find two inventions that changed how people travel.",
            goal = 2,
            emoji = "💡",
            rubric = "Counts: an invention for getting around (bicycle, steam locomotive, airplane, automobile, hot " +
                "air balloon, helicopter, submarine, jet engine). The page must describe it as an invention or tell its story.",
            answers = listOf(
                a("Bicycle", "pedal power for getting around."),
                a("Steam locomotive", "trains pulled by steam engines.", "steam locomotive", "locomotive"),
                a("Airplane", "the Wright brothers flew the first one in 1903.", "airplane", "aeroplane", "wright brothers"),
                a("Automobile", "cars changed how people travel.", "automobile", "motor car"),
                a("Hot air balloon", "the first people flew in one in 1783.", "hot air balloon"),
                a("Helicopter", "it can fly straight up and hover."),
                a("Submarine", "it travels under the water."),
                a("Jet engine", "it made fast passenger planes possible.", "jet engine"),
            ),
        ),
    )

    private val TOPIC_PATTERNS: List<Pair<Template, List<Regex>>> by lazy {
        TEMPLATES.map { t -> t to t.topicWords.map { TextKit.keywordRegex(it) } }
    }

    /** The template whose topic words best match the titles (earlier titles weigh a little more). */
    fun pick(recentTitles: List<String>): Template {
        val titles = recentTitles.map { TextKit.normalize(it) }.filter { it.isNotBlank() }.take(12)
        if (titles.isEmpty()) return RECORD_BREAKERS
        var best: Template? = null
        var bestScore = 0.0
        for ((template, patterns) in TOPIC_PATTERNS) {
            var score = 0.0
            titles.forEachIndexed { i, title ->
                val weight = 1.0 / (1 + i * 0.25)
                patterns.forEach { if (it.containsMatchIn(title)) score += weight }
            }
            if (score > bestScore) {
                bestScore = score
                best = template
            }
        }
        return best ?: RECORD_BREAKERS
    }
}
