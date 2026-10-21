package tv.wander.ai

/**
 * Keyword -> emoji + one-word icon, for cards built on the TV. Ordered specific-first: the first keyword found
 * wins. Only emoji from Emoji 5.0 (2017) or older, so older Fire OS emoji fonts render every one of them.
 */
internal object EmojiMap {
    data class Hit(val emoji: String, val icon: String)

    private val ENTRIES: List<Triple<String, String, String>> = listOf(
        // animals
        Triple("cheetah", "🐆", "speed"), Triple("leopard", "🐆", "animal"), Triple("jaguar", "🐆", "animal"),
        Triple("lion", "🦁", "animal"), Triple("tiger", "🐅", "animal"), Triple("wolf", "🐺", "animal"),
        Triple("fox", "🦊", "animal"), Triple("panda", "🐼", "animal"), Triple("koala", "🐨", "animal"),
        Triple("bear", "🐻", "animal"), Triple("gorilla", "🦍", "animal"), Triple("monkey", "🐒", "animal"),
        Triple("elephant", "🐘", "animal"), Triple("giraffe", "🦒", "animal"), Triple("zebra", "🦓", "animal"),
        Triple("horse", "🐎", "animal"), Triple("rhino", "🦏", "animal"), Triple("rabbit", "🐇", "animal"),
        Triple("mouse", "🐁", "animal"), Triple("bat", "🦇", "animal"), Triple("deer", "🦌", "animal"),
        Triple("camel", "🐫", "animal"), Triple("pig", "🐖", "animal"), Triple("cow", "🐄", "animal"),
        Triple("sheep", "🐑", "animal"), Triple("dog", "🐕", "animal"), Triple("cat", "🐈", "animal"),
        Triple("frog", "🐸", "animal"), Triple("turtle", "🐢", "animal"), Triple("tortoise", "🐢", "animal"),
        Triple("snake", "🐍", "animal"), Triple("lizard", "🦎", "animal"), Triple("crocodile", "🐊", "animal"),
        Triple("alligator", "🐊", "animal"),
        // birds
        Triple("falcon", "🦅", "bird"), Triple("eagle", "🦅", "bird"), Triple("hawk", "🦅", "bird"),
        Triple("owl", "🦉", "bird"), Triple("penguin", "🐧", "bird"), Triple("duck", "🦆", "bird"),
        Triple("chicken", "🐔", "bird"), Triple("bird", "🐦", "bird"), Triple("feather", "🐦", "bird"),
        // ocean
        Triple("whale", "🐋", "ocean"), Triple("dolphin", "🐬", "ocean"), Triple("shark", "🦈", "ocean"),
        Triple("octopus", "🐙", "ocean"), Triple("squid", "🦑", "ocean"), Triple("crab", "🦀", "ocean"),
        Triple("shrimp", "🦐", "ocean"), Triple("coral", "🐠", "ocean"), Triple("reef", "🐠", "ocean"),
        Triple("shell", "🐚", "ocean"), Triple("fish", "🐟", "ocean"),
        // small creatures
        Triple("ant", "🐜", "bug"), Triple("bee", "🐝", "bug"), Triple("butterfly", "🦋", "bug"),
        Triple("moth", "🦋", "bug"), Triple("beetle", "🐞", "bug"), Triple("ladybug", "🐞", "bug"),
        Triple("spider", "🕷", "bug"), Triple("scorpion", "🦂", "bug"), Triple("snail", "🐌", "bug"),
        Triple("caterpillar", "🐛", "bug"), Triple("insect", "🐛", "bug"),
        // dinosaurs
        Triple("tyrannosaurus", "🦖", "dinosaur"), Triple("rex", "🦖", "dinosaur"), Triple("raptor", "🦖", "dinosaur"),
        Triple("dinosaur", "🦕", "dinosaur"), Triple("fossil", "🦕", "dinosaur"), Triple("jurassic", "🦕", "dinosaur"),
        Triple("cretaceous", "🦕", "dinosaur"),
        // space
        Triple("astronaut", "🚀", "space"), Triple("rocket", "🚀", "space"), Triple("satellite", "🛰", "space"),
        Triple("telescope", "🔭", "space"), Triple("galaxy", "🌌", "space"), Triple("comet", "☄️", "space"),
        Triple("meteor", "🌠", "space"), Triple("planet", "🌍", "planet"), Triple("earth", "🌍", "planet"),
        Triple("moon", "🌕", "moon"), Triple("sun", "☀️", "sun"), Triple("star", "⭐", "star"),
        Triple("orbit", "🛰", "space"), Triple("space", "🚀", "space"),
        // weather + nature
        Triple("volcano", "🌋", "nature"), Triple("tornado", "🌪", "weather"), Triple("hurricane", "🌀", "weather"),
        Triple("lightning", "⚡", "weather"), Triple("thunder", "⛈", "weather"), Triple("storm", "⛈", "weather"),
        Triple("snow", "❄️", "weather"), Triple("ice", "❄️", "weather"), Triple("rain", "🌧", "weather"),
        Triple("rainbow", "🌈", "weather"), Triple("cloud", "☁️", "weather"), Triple("wind", "💨", "weather"),
        Triple("desert", "🏜", "nature"), Triple("mountain", "⛰", "nature"), Triple("island", "🏝", "nature"),
        Triple("river", "🏞", "nature"), Triple("forest", "🌲", "nature"), Triple("tree", "🌳", "plant"),
        Triple("flower", "🌸", "plant"), Triple("cactus", "🌵", "plant"), Triple("mushroom", "🍄", "plant"),
        Triple("leaf", "🍃", "plant"), Triple("seed", "🌱", "plant"), Triple("plant", "🌱", "plant"),
        // body
        Triple("brain", "🧠", "body"), Triple("heart", "❤️", "body"), Triple("muscle", "💪", "body"),
        Triple("skeleton", "💀", "body"), Triple("eye", "👁", "body"), Triple("ear", "👂", "body"),
        Triple("nose", "👃", "body"),
        // food
        Triple("chocolate", "🍫", "food"), Triple("pizza", "🍕", "food"), Triple("bread", "🍞", "food"),
        Triple("cheese", "🧀", "food"), Triple("honey", "🍯", "food"), Triple("apple", "🍎", "food"),
        Triple("banana", "🍌", "food"), Triple("tomato", "🍅", "food"), Triple("carrot", "🥕", "food"),
        Triple("egg", "🥚", "food"), Triple("milk", "🥛", "food"), Triple("cake", "🍰", "food"),
        Triple("fruit", "🍎", "food"), Triple("food", "🍽", "food"),
        // music, sport, history, science, travel
        Triple("guitar", "🎸", "music"), Triple("piano", "🎹", "music"), Triple("violin", "🎻", "music"),
        Triple("orchestra", "🎻", "music"), Triple("drum", "🥁", "music"), Triple("trumpet", "🎺", "music"),
        Triple("music", "🎵", "music"), Triple("song", "🎵", "music"), Triple("dance", "💃", "music"),
        Triple("soccer", "⚽", "sport"), Triple("football", "⚽", "sport"), Triple("basketball", "🏀", "sport"),
        Triple("tennis", "🎾", "sport"), Triple("baseball", "⚾", "sport"), Triple("olympic", "🏅", "sport"),
        Triple("medal", "🏅", "sport"), Triple("swim", "🏊", "sport"), Triple("sport", "🏅", "sport"),
        Triple("pyramid", "🏛", "history"), Triple("castle", "🏰", "history"), Triple("king", "👑", "history"),
        Triple("queen", "👑", "history"), Triple("pharaoh", "👑", "history"), Triple("ancient", "🏛", "history"),
        Triple("museum", "🏛", "history"), Triple("history", "📜", "history"), Triple("map", "🗺", "map"),
        Triple("robot", "🤖", "science"), Triple("computer", "💻", "science"), Triple("invention", "💡", "science"),
        Triple("electricity", "⚡", "science"), Triple("magnet", "🔬", "science"),Triple("science", "🔬", "science"),
        Triple("train", "🚆", "travel"), Triple("airplane", "✈️", "travel"), Triple("plane", "✈️", "travel"),
        Triple("ship", "🚢", "travel"), Triple("boat", "⛵", "travel"), Triple("car", "🚗", "travel"),
        Triple("bicycle", "🚲", "travel"),
        // generic, last
        Triple("ocean", "🌊", "ocean"), Triple("sea", "🌊", "ocean"), Triple("water", "💧", "water"),
        Triple("fire", "🔥", "fire"), Triple("glow", "✨", "light"), Triple("light", "💡", "light"),
        Triple("speed", "⚡", "speed"), Triple("fast", "⚡", "speed"), Triple("record", "🏆", "record"),
        Triple("baby", "🐣", "family"), Triple("babies", "🐣", "family"), Triple("cub", "🐣", "family"),
        Triple("book", "📚", "book"), Triple("art", "🎨", "art"), Triple("color", "🎨", "art"),
        Triple("time", "⏰", "time"), Triple("city", "🏙", "city"),
    )

    val size: Int get() = ENTRIES.size

    private val PATTERNS: List<Pair<Regex, Hit>> by lazy {
        ENTRIES.map { (k, e, i) -> TextKit.keywordRegex(k) to Hit(e, i) }
    }

    /**
     * First keyword hit across [texts], checked in order (a card's line first, then its title, then the page).
     * Hits whose emoji equals [exclude] are skipped, so a deck about cheetahs is not five cheetahs in a row.
     */
    fun find(vararg texts: String?, exclude: String? = null): Hit? {
        for (t in texts) {
            if (t.isNullOrBlank()) continue
            val n = TextKit.normalize(t)
            for ((regex, hit) in PATTERNS) {
                if (hit.emoji != exclude && regex.containsMatchIn(n)) return hit
            }
        }
        return null
    }

    const val DEFAULT = "✨"
}
