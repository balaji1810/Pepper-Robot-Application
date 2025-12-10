"""Flask API blueprint exposing /api/chat.

Validates JSON input, rate-limits, and routes to ChatAgent.
"""
from __future__ import annotations

import uuid

from flask import Blueprint, jsonify, request

from .chain import ChatAgent

api_bp = Blueprint("api", __name__, url_prefix="/api")


def create_api(agent: ChatAgent) -> Blueprint:

    @api_bp.route("/chat", methods=["POST"])
    def chat():
        # Content type enforcement
        if request.content_type != "application/json":
            return jsonify({"ok": False, "error": "Content-Type must be application/json"}), 415

        data = request.get_json(silent=True)
        if not isinstance(data, dict):
            return jsonify({"ok": False, "error": "Invalid JSON body"}), 400

        text = (data.get("text") or "").strip()
        session_id = (data.get("session_id") or "").strip()

        if not text:
            return jsonify({"ok": False, "error": "Field 'text' is required"}), 400
        if len(text) > 2000:
            return jsonify({"ok": False, "error": "Text too long (max 2000 chars)"}), 400

        if not session_id:
            session_id = uuid.uuid4().hex

        try:
            reply = agent.reply(session_id, text)
        except TimeoutError:
            return jsonify({"ok": False, "error": "LLM timeout"}), 502
        except Exception as e:
            return jsonify({"ok": False, "error": f"Unexpected error: {e}"}), 500

        return jsonify({
            "ok": True,
            "session_id": session_id,
            "reply": reply,
        }), 200

    return api_bp
