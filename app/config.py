import os
from dataclasses import dataclass

from dotenv import load_dotenv

@dataclass
class Config:
    """Simple configuration container for the application."""
    MISTRAL_API_KEY: str | None
    API_AUTH_TOKEN: str | None
    USE_REDIS: bool
    REDIS_URL: str | None
    LLM_TEMPERATURE: float
    HOST: str
    PORT: int
    ENV: str


def load_config() -> Config:
    # Load variables from a .env file if present
    load_dotenv()

    mistral_key = os.getenv("MISTRAL_API_KEY")
    api_token = os.getenv("API_AUTH_TOKEN")
    env = os.getenv("FLASK_ENV", "production")
    host = os.getenv("FLASK_HOST", "0.0.0.0")
    port_str = os.getenv("FLASK_PORT", "5000")
    use_redis_str = os.getenv("USE_REDIS", "0")
    redis_url = os.getenv("REDIS_URL")
    temp_str = os.getenv("LLM_TEMPERATURE", "0.7")

    # Validate required secrets
    missing = []
    if not mistral_key:
        missing.append("MISTRAL_API_KEY")
    if not api_token:
        missing.append("API_AUTH_TOKEN")

    if missing:
        raise ValueError(
            "Missing required environment variables: " + ", ".join(missing)
        )

    try:
        port = int(port_str)
    except ValueError:
        raise ValueError("FLASK_PORT must be an integer")

    try:
        use_redis = bool(int(use_redis_str))
    except ValueError:
        raise ValueError("USE_REDIS must be 0 or 1")

    try:
        temperature = float(temp_str)
    except ValueError:
        raise ValueError("LLM_TEMPERATURE must be a float")

    if use_redis and not redis_url:
        raise ValueError("REDIS_URL is required when USE_REDIS=1")

    return Config(
        MISTRAL_API_KEY=mistral_key,
        API_AUTH_TOKEN=api_token,
        USE_REDIS=use_redis,
        REDIS_URL=redis_url,
        LLM_TEMPERATURE=temperature,
        HOST=host,
        PORT=port,
        ENV=env,
    )
