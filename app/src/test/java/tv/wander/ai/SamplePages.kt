package tv.wander.ai

import tv.wander.model.OfflineAnswer
import tv.wander.model.PageText
import tv.wander.model.Quest

/** Page texts shaped like Readability.js output on the TV (written for these tests). */
object SamplePages {
    val CHEETAH = PageText(
        url = "https://simple.wikipedia.org/wiki/Cheetah",
        title = "Cheetah - Simple English Wikipedia, the free encyclopedia",
        siteName = "Wikipedia",
        headings = listOf("Speed", "Food", "Family life", "References"),
        text = """
            The cheetah (/ˈtʃiːtə/; Acinonyx jubatus) is a large wild cat that lives mostly in Africa, with a small group in Iran.[1]
            It is the fastest land animal. A cheetah can sprint at about 110 km/h (68 mph) for short bursts, which is faster than a car on the highway.[2]
            Speed
            Its long legs, light body and flexible spine let it cover about 7 meters in a single stride. The long tail works like a rudder when it turns.
            A chase usually lasts less than a minute, because running that fast makes the cheetah very hot and tired.
            Food
            Cheetahs hunt during the day, mostly gazelles and impalas. They rely on their sharp eyesight rather than smell.
            Family life
            A mother cheetah raises her cubs alone for about 18 months. The cubs have fluffy grey fur on their backs that helps them hide in tall grass.
            Today fewer than 7,000 adult cheetahs live in the wild, so many groups work to protect them.
            References
            Retrieved from the cheetah conservation report, 2024. ISBN 978-0-00-000000-0.
        """.trimIndent(),
    )

    val SAILFISH = PageText(
        url = "https://simple.wikipedia.org/wiki/Sailfish",
        title = "Sailfish",
        text = """
            The sailfish is a fish with a huge fin on its back that looks like a sail.
            Sailfish are among the fastest fish in the ocean, and some people think a sailfish can swim at over 100 km/h.
            When a sailfish hunts, it raises its sail to herd small fish. Cheetahs are often compared with sailfish, but cheetahs live on land.
        """.trimIndent(),
    )

    val SNOW_LEOPARD = PageText(
        url = "https://simple.wikipedia.org/wiki/Snow_leopard",
        title = "Snow leopard",
        text = """
            The snow leopard is a big cat that lives high in the mountains of Central Asia.
            A snow leopard wraps its thick tail around its face to stay warm while it sleeps.
            Unlike other leopards, the snow leopard cannot roar.
        """.trimIndent(),
    )

    val MENU_ONLY = PageText(
        url = "https://example.org/",
        title = "Home",
        text = "Home\nAbout\nContact\nSign in to continue.",
    )

    val SPEED_SAFARI = Quest(
        id = "speed-safari",
        title = "Speed Safari",
        prompt = "Find three animals that are faster than a car on the highway (over 100 km/h, 62 mph).",
        goal = 3,
        emoji = "🐆",
        rubric = "Counts: an animal whose stated top speed is over 100 km/h or 62 mph.",
        offline = listOf(
            OfflineAnswer("Cheetah", listOf("cheetah"), "Cheetahs sprint about 110 km/h."),
            OfflineAnswer("Peregrine falcon", listOf("peregrine falcon", "peregrine"), "Peregrine falcons dive at over 300 km/h."),
            OfflineAnswer("Sailfish", listOf("sailfish"), "Sailfish may swim at over 100 km/h."),
        ),
    )

    val BIG_CATS = Quest(
        id = "lumo-cats",
        title = "Big Cat Count",
        prompt = "Find three kinds of big wild cats.",
        goal = 3,
        emoji = "🐯",
        rubric = "Large wild cats.",
        offline = listOf(
            OfflineAnswer("Leopard", listOf("leopard"), "Leopards climb trees."),
            OfflineAnswer("Snow leopard", listOf("snow leopard"), "It lives in the mountains."),
        ),
        generated = true,
    )
}
