"""Tool definitions for forced tool use. The JSON schema IS the output contract: Bedrock Converse is called with
toolChoice = this one tool, so the model's answer always arrives as structured toolUse.input."""

from dataclasses import dataclass

from .prompts import DETOUR_MENU, QUEST_MENU


@dataclass(frozen=True)
class Tool:
    name: str
    description: str
    schema: dict
    max_tokens: int

    def spec(self) -> dict:
        return {"toolSpec": {"name": self.name, "description": self.description, "inputSchema": {"json": self.schema}}}


MAKE_CARDS = Tool(
    name="make_cards",
    description="Return 3 to 5 big, kid-friendly field-guide cards that summarize the web page for a TV screen.",
    max_tokens=900,
    schema={
        "type": "object",
        "properties": {
            "cards": {
                "type": "array",
                "minItems": 3,
                "maxItems": 5,
                "items": {
                    "type": "object",
                    "properties": {
                        "title": {"type": "string", "description": "2-5 word heading, max 32 characters, no emoji"},
                        "line": {"type": "string", "description": "one sentence, max 110 characters, a fact from the page"},
                        "emoji": {"type": "string", "description": "exactly one emoji"},
                        "icon": {"type": "string", "description": "one lowercase theme word, like speed or planet"},
                    },
                    "required": ["title", "line", "emoji", "icon"],
                },
            }
        },
        "required": ["cards"],
    },
)

JUDGE_QUEST = Tool(
    name="judge_quest",
    description="Judge whether the web page proves an item for the family quest, quoting the page as evidence.",
    max_tokens=400,
    schema={
        "type": "object",
        "properties": {
            "pass": {"type": "boolean", "description": "true only if the page proves an item meets the rubric"},
            "item": {"type": "string", "description": "common name of the item, or empty string"},
            "evidence": {"type": "string", "description": "word-for-word quote from the page, max 140 characters, or empty"},
            "reason": {"type": "string", "description": "one friendly sentence for Lumo to say, max 120 characters"},
        },
        "required": ["pass", "item", "evidence", "reason"],
    },
)

NEW_QUEST = Tool(
    name="new_quest",
    description="Create one new family quest that can be solved on encyclopedia-style web pages.",
    max_tokens=500,
    schema={
        "type": "object",
        "properties": {
            "title": {"type": "string", "description": "2-4 playful words, max 32 characters"},
            "prompt": {"type": "string", "description": "one sentence starting with Find, max 140 characters"},
            "goal": {"type": "integer", "minimum": 2, "maximum": 3},
            "emoji": {"type": "string", "description": "exactly one emoji"},
            "rubric": {"type": "string", "description": "what counts for the judge, max 400 characters"},
        },
        "required": ["title", "prompt", "goal", "emoji", "rubric"],
    },
)

LUMO_REACT = Tool(
    name="lumo_react",
    description="Lumo's mood, one short line, and one better thing to do when browsing gets aimless.",
    max_tokens=300,
    schema={
        "type": "object",
        "properties": {
            "mood": {"type": "string", "enum": ["curious", "happy", "sleepy", "concerned"]},
            "line": {"type": "string", "description": "what Lumo says, max 120 characters"},
            "suggestion_kind": {"type": "string", "enum": ["quest", "detour", "none"]},
            "quest_id": {"type": "string", "enum": list(QUEST_MENU.keys()) + [""]},
            "detour": {"type": "string", "enum": list(DETOUR_MENU.keys()) + [""]},
            "label": {"type": "string", "description": "button text, max 40 characters"},
            "detail": {"type": "string", "description": "why it is fun, max 80 characters"},
        },
        "required": ["mood", "line", "suggestion_kind", "label", "detail"],
    },
)
