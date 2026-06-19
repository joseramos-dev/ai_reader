# Multi-Engine TTS FastAPI Backend

REST API for speech synthesis with **Pocket-TTS**, **Kokoro**, and **Piper**.
Compare engines side-by-side from the PDF reader UI. Default model: `spanish_24l` (Pocket-TTS + Lola).

## Quick start

### Prerequisites

- [uv](https://docs.astral.sh/uv/getting-started/installation/) (recommended) or Python 3.10+
- **espeak-ng** — required for Kokoro and Piper ([Windows MSI](https://github.com/espeak-ng/espeak-ng/releases))
- Hugging Face account with accepted [Pocket-TTS model terms](https://huggingface.co/kyutai/pocket-tts) (for Pocket-TTS voice cloning; predefined voices work without auth)

### Install and run

```bash
cd backend
cp .env.example .env   # if present
uv sync
uv run python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

On Windows, prefer `python -m uvicorn` over `uv run uvicorn` — the entry-point shim can break after mixing `pip install` with `uv`.

### Troubleshooting: `uv trampoline failed to canonicalize script path`

This usually means a broken script wrapper in `.venv\Scripts\` (often after `pip install` inside a uv-managed venv).

```powershell
# Fix: reinstall the affected package
uv sync --reinstall-package uvicorn

# Or run without the shim:
uv run python -m uvicorn app.main:app --host 0.0.0.0 --port 8000

# Last resort: recreate the venv
Remove-Item -Recurse -Force .venv
uv sync
```

On first startup, the backend preloads the **default engine** (`pocket_tts`). Switching engines in the UI unloads the previous model and loads the new one (one engine in RAM/VRAM at a time).

Open **http://localhost:8000/docs** for Swagger UI.

### Configuration

| Variable            | Default       | Description                          |
|---------------------|---------------|--------------------------------------|
| `TTS_LANGUAGE`      | `spanish_24l` | Pocket-TTS language variant          |
| `TTS_DEFAULT_VOICE` | `lola`        | Legacy default voice id              |
| `TTS_QUANTIZE`      | `true`        | Pocket-TTS int8 quantization         |
| `HOST`              | `0.0.0.0`     | Bind address                         |
| `PORT`              | `8000`        | Port                                 |

## Engines and voices

`GET /voices` returns a curated catalogue: **5 voices per engine** (4 Spanish + 1 English).

| Engine       | Type        | Notes                                      |
|--------------|-------------|--------------------------------------------|
| `pocket_tts` | Acoustic    | CPU-friendly; default at startup           |
| `kokoro`     | 82M acoustic| Needs espeak-ng; `lang_code` e/a           |
| `piper`      | ONNX VITS   | Needs espeak-ng; ONNX cached in `data/piper/` |

## Endpoints

| Method | Path                         | Description                    |
|--------|------------------------------|--------------------------------|
| GET    | `/health`                    | Multi-engine status + memory   |
| GET    | `/voices`                    | Curated voice catalogue        |
| POST   | `/api/v1/tts/engine/load`    | Switch active engine           |
| POST   | `/api/v1/tts`                | Synthesize WAV (active engine) |
| WS     | `/api/v1/tts/read`           | Read-aloud session             |

### Load engine

```bash
curl -X POST http://localhost:8000/api/v1/tts/engine/load \
  -H "Content-Type: application/json" \
  -d '{"engine": "kokoro"}'
```

### WebSocket read-aloud

```json
{"action": "start", "engine": "pocket_tts", "voice": "lola"}
{"action": "enqueue", "phrases": ["Primer párrafo.", "Segundo párrafo."]}
{"action": "clear"}
{"action": "stop"}
```

## Pipeline

```
PDF extract → chunk (per-engine max chars) → WS enqueue → TTS synth → WAV → playback queue
```

- Backend text queue: max 3 chunks (backpressure)
- Frontend playback queue: max 2 decoded buffers
- Single synthesis thread (`Semaphore(1)`) — prefetch overlaps synth with playback

## System requirements by engine

| Engine     | RAM (approx.) | GPU      | System deps   |
|------------|---------------|----------|---------------|
| pocket_tts | 1–2 GB        | Optional | —             |
| kokoro     | ~500 MB       | Optional | espeak-ng     |
| piper      | ~200 MB/voice | Optional | espeak-ng     |

## Piper voice download

Piper ONNX models download automatically on first use into `./data/piper/`. You can pre-download:

```bash
uv run python -m piper.download_voices es_ES-carlfm-x_low --data-dir ./data/piper
```

## Docker

```bash
docker build -t multi-tts-api .
docker run -p 8000:8000 \
  -v "$HOME/.cache/huggingface:/root/.cache/huggingface" \
  multi-tts-api
```

Install espeak-ng in the image for Kokoro/Piper support.
