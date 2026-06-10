from __future__ import annotations

import os
from dataclasses import dataclass, field


DEFAULT_DATABASE_URL = "sqlite:///./data/rca-dev.db"


@dataclass(frozen=True)
class LlmSettings:
    provider: str = "disabled"
    model: str | None = None
    api_key: str | None = None
    base_url: str | None = None
    timeout_seconds: float = 20.0
    max_output_tokens: int = 1200


@dataclass(frozen=True)
class Settings:
    database_url: str = DEFAULT_DATABASE_URL
    auto_create_tables: bool = False
    llm: LlmSettings = field(default_factory=LlmSettings)


def load_settings(database_url: str | None = None, auto_create_tables: bool | None = None) -> Settings:
    auto_create_value = (
        auto_create_tables
        if auto_create_tables is not None
        else os.getenv("RCA_AUTO_CREATE_TABLES", "false").lower() == "true"
    )
    return Settings(
        database_url=database_url or os.getenv("RCA_DATABASE_URL", DEFAULT_DATABASE_URL),
        auto_create_tables=auto_create_value,
        llm=_load_llm_settings(),
    )


def normalize_database_url(database_url: str) -> str:
    if database_url.startswith("postgres://"):
        return "postgresql+psycopg://" + database_url.removeprefix("postgres://")
    if database_url.startswith("postgresql://"):
        return "postgresql+psycopg://" + database_url.removeprefix("postgresql://")
    if database_url.startswith("mariadb://"):
        return "mysql+pymysql://" + database_url.removeprefix("mariadb://")
    if database_url.startswith("mysql://"):
        return "mysql+pymysql://" + database_url.removeprefix("mysql://")
    return database_url


def _load_llm_settings() -> LlmSettings:
    return LlmSettings(
        provider=os.getenv("RCA_LLM_PROVIDER", "disabled").strip().lower(),
        model=_empty_to_none(os.getenv("RCA_LLM_MODEL")),
        api_key=_empty_to_none(os.getenv("RCA_LLM_API_KEY")),
        base_url=_empty_to_none(os.getenv("RCA_LLM_BASE_URL")),
        timeout_seconds=_float_env("RCA_LLM_TIMEOUT_SECONDS", 20.0),
        max_output_tokens=_int_env("RCA_LLM_MAX_OUTPUT_TOKENS", 1200),
    )


def _empty_to_none(value: str | None) -> str | None:
    if value is None:
        return None
    value = value.strip()
    return value or None


def _float_env(name: str, default: float) -> float:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        return float(value)
    except ValueError:
        return default


def _int_env(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        return int(value)
    except ValueError:
        return default
