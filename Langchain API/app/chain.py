import logging

from langchain_core.prompts import PromptTemplate
from langchain_core.output_parsers import StrOutputParser
from langchain_core.runnables import RunnableSerializable
from langchain_mistralai import ChatMistralAI

from .config import Config
from .memory_store import InMemoryStore, ConversationPair, RedisStore

logger = logging.getLogger(__name__)


SYSTEM_PROMPT = (
    "You are a helpful assistant for a Pepper robot. "
    "You must respond politely, concisely (<= 200 words), and avoid giving legal/medical advice."
)

PROMPT_TEMPLATE = (
    "System: "
    + SYSTEM_PROMPT
    + "\n\nConversation so far:\n{history}\n\n"
    + "User: {user_input}\nAssistant:"
)


def format_history(pairs: list[ConversationPair]) -> str:
    lines: list[str] = []
    for user, assistant in pairs:
        lines.append(f"User: {user}")
        lines.append(f"Assistant: {assistant}")
    return "\n".join(lines)


class ChatAgent:

    def __init__(self, config: Config, memory_store: RedisStore | InMemoryStore) -> None:
        self.config = config
        self.memory_store = memory_store

        self.llm = ChatMistralAI(
            temperature=config.LLM_TEMPERATURE,
            max_retries=2,
            timeout=30,
        )
        self.prompt = PromptTemplate(
            input_variables=["history", "user_input"],
            template=PROMPT_TEMPLATE,
        )

        # Simple chain: prompt -> llm -> parser
        self.chain: RunnableSerializable = self.prompt | self.llm | StrOutputParser()

    def reply(self, session_id: str, text: str) -> str:
        history_pairs = self.memory_store.get(session_id)
        history_str = format_history(history_pairs)

        try:
            response: str = self.chain.invoke({
                "history": history_str,
                "user_input": text,
            })
        except Exception as e:
            msg = str(e).lower()
            if "timeout" in msg or "timed out" in msg:
                raise TimeoutError("LLM timeout")
            raise

        # words = response.split()
        # if len(words) > 200:
        #     response = " ".join(words[:200])

        # Update memory
        try:
            self.memory_store.append(session_id, text, response)
        except Exception:
            logger.exception("Failed to append to memory store")

        return response
