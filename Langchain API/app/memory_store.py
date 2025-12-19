# from abc import ABC, abstractmethod

try:
    import redis
except Exception:
    redis = None  # Optional dependency if not using Redis


ConversationPair = tuple[str, str]


# class MemoryStore(ABC):
#     """Abstract memory store for session conversations."""

#     @abstractmethod
#     def get(self, session_id: str) -> List[ConversationPair]:

#     @abstractmethod
#     def append(self, session_id: str, user_text: str, assistant_text: str) -> None:


class InMemoryStore():

    def __init__(self) -> None:
        self._store: dict[str, list[ConversationPair]] = {}

    def get(self, session_id: str) -> list[ConversationPair]:
        return list(self._store.get(session_id, []))

    def append(self, session_id: str, user_text: str, assistant_text: str) -> None:
        self._store.setdefault(session_id, []).append((user_text, assistant_text))


class RedisStore():

    def __init__(self, redis_url: str, ttl_seconds: int = 86400) -> None:
        if redis is None:
            raise RuntimeError("redis package not available; install redis or set USE_REDIS=0")
        self._client = redis.Redis.from_url(redis_url, decode_responses=True)
        self._ttl = ttl_seconds
        # simple separator unlikely to appear
        self._sep = "\u241E"

    def _key(self, session_id: str) -> str:
        return f"pepper:chat:{session_id}"

    def get(self, session_id: str) -> list[ConversationPair]:
        key = self._key(session_id)
        items = self._client.lrange(key, 0, -1)
        result: list[ConversationPair] = []
        for raw in items: # type: ignore
            try:
                # s = raw.decode("utf-8")
                # user, assistant = s.split(self._sep, 1)
                # result.append((user, assistant))
                s = raw
                user, assistant = s.split(self._sep, 1)
                result.append((user, assistant))
            except Exception as e:
                raise RuntimeError(f"Corrupted data in Redis for session {session_id}: {e}")
        # Touch key to extend TTL
        if result:
            self._client.expire(key, self._ttl)
        return result

    def append(self, session_id: str, user_text: str, assistant_text: str) -> None:
        key = self._key(session_id)
        payload = f"{user_text}{self._sep}{assistant_text}"
        pipeline = self._client.pipeline()
        pipeline.rpush(key, payload)
        pipeline.expire(key, self._ttl)
        pipeline.execute()
