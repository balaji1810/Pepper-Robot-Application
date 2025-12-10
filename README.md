# Pepper LangChain Chat API

A Flask HTTP API wrapping a LangChain conversational agent using Mistral AI. Supports per-session memory (in-memory by default, optional Redis) and simple Bearer token authentication.

## Features
- LangChain + Mistral (`langchain-mistralai`) chat agent
- Per-session conversation memory with in-memory or Redis
- `POST /api/chat` JSON endpoint
- Config via environment variables (`.env`)

## Setup
1. Create and activate a Python environment, e.g. with Conda:
```cmd
conda create -n pepper-chat python=3.13.5 -y
conda activate pepper-chat
```
2. Install dependencies:
```cmd
pip install -r requirements.txt
```
3. Create `.env` from the template and fill secrets:
```
cp .env.example .env
```
Edit `.env` and set `MISTRAL_API_KEY` and `API_AUTH_TOKEN`.

## Run
```cmd
python -m app.main
```
Server defaults to `http://localhost:5000`.

## Example Request
```cmd
curl -X POST http://localhost:5000/api/chat ^
  -H "Content-Type: application/json" ^
  -H "Authorization: Bearer supersecretapitoken" ^
  -d "{\"text\":\"Hi Pepper, explain LangChain\",\"session_id\":\"test1\"}"
```

Response:
```json
{
  "ok": true,
  "session_id": "test1",
  "reply": "Hello!"
}
```

## Redis (Optional)
To persist session memory, set `USE_REDIS=1` and provide `REDIS_URL` in `.env`.

## Environment Variables
See `.env.example` for all configurable options.

## Project Structure
```
pepper_langchain_chat/
├── app/
│   ├── __init__.py
│   ├── main.py
│   ├── api.py
│   ├── chain.py
│   ├── memory_store.py
│   ├── auth.py
│   ├── config.py
├── .env.example
├── requirements.txt
├── README.md
|── .gitignore
|── LICENSE
```
