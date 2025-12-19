from flask import jsonify, request

from .config import Config

def require_api_token(config: Config):
    def _check():
        auth_header = request.headers.get("Authorization", "")
        prefix = "Bearer "
        if not auth_header.startswith(prefix):
            return jsonify({"error": "Unauthorized"}), 401
        token = auth_header[len(prefix) :]
        if token != config.API_AUTH_TOKEN:
            return jsonify({"error": "Unauthorized"}), 401
        return None  # OK

    return _check
