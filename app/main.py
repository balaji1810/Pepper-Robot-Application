from flask import Flask

from .config import load_config
from .memory_store import InMemoryStore, RedisStore
from .chain import ChatAgent
from .auth import require_api_token
from .api import create_api


def create_app() -> Flask:
    config = load_config()

    # Choose memory store based on config
    if config.USE_REDIS and config.REDIS_URL:
        memory = RedisStore(config.REDIS_URL)
    else:
        memory = InMemoryStore()

    agent = ChatAgent(config=config, memory_store=memory)

    app = Flask(__name__)

    app.before_request(require_api_token(config))

    app.register_blueprint(create_api(agent))

    # Simple health check
    @app.route("/health")
    def health():
        return {"status": "ok"}

    return app


if __name__ == "__main__":
    app = create_app()
    cfg = load_config()
    app.run(host=cfg.HOST, port=cfg.PORT, debug=(cfg.ENV == "development"))
