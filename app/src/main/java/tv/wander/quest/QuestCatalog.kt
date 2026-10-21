package tv.wander.quest

import tv.wander.model.Quest

/** The curated family quests. Each carries an AI rubric plus offline answers so it works without the cloud. */
object QuestCatalog {
    val all: List<Quest> = emptyList()

    fun byId(id: String): Quest? = all.firstOrNull { it.id == id }
}
