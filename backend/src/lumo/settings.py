"""Runtime settings from Lambda environment variables (read per request, so tests can change them)."""

import os
from dataclasses import dataclass

DEFAULT_MODEL_ID = "eu.anthropic.claude-haiku-5-5"
DEFAULT_FALLBACK_MODEL_ID = "eu.amazon.nova-2-lite-v1:0"
DEFAULT_VOICE = "Ivy"

# Amazon Polly voices that support the neural engine (English). Lumo defaults to Ivy, a child voice.
NEURAL_VOICES = {
    "Ivy", "Kevin", "Justin", "Joanna", "Salli", "Kimberly", "Kendra", "Joey", "Matthew", "Ruth", "Stephen",
    "Danielle", "Gregory", "Amy", "Emma", "Brian", "Arthur", "Olivia", "Aria", "Ayanda", "Niamh", "Kajal",
}


@dataclass(frozen=True)
class Settings:
    api_key: str
    model_id: str
    fallback_model_id: str
    voice_id: str
    table_name: str
    guardrail_id: str
    guardrail_version: str
    region: str
    cache_days: int

    @property
    def guardrail_on(self) -> bool:
        return bool(self.guardrail_id)

    @staticmethod
    def load() -> "Settings":
        env = os.environ.get
        voice = (env("VOICE_ID") or DEFAULT_VOICE).strip()
        try:
            cache_days = max(1, min(30, int(env("CACHE_TTL_DAYS") or "7")))
        except ValueError:
            cache_days = 7
        guardrail_id = (env("GUARDRAIL_ID") or "").strip()
        return Settings(
            api_key=(env("API_KEY") or "").strip(),
            model_id=(env("MODEL_ID") or DEFAULT_MODEL_ID).strip(),
            fallback_model_id=(env("FALLBACK_MODEL_ID") or "").strip(),
            voice_id=voice if voice in NEURAL_VOICES else DEFAULT_VOICE,
            table_name=(env("TABLE_NAME") or "").strip(),
            guardrail_id=guardrail_id,
            guardrail_version=(env("GUARDRAIL_VERSION") or "DRAFT").strip() if guardrail_id else "",
            region=(env("AWS_REGION") or env("AWS_DEFAULT_REGION") or "eu-central-1").strip(),
            cache_days=cache_days,
        )
