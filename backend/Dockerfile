FROM ghcr.io/astral-sh/uv:debian

WORKDIR /app

# Copy dependency manifests first for layer caching
COPY pyproject.toml .
COPY uv.lock* .

# Copy source
COPY app ./app

# Install dependencies (CPU-only PyTorch via the pytorch-cpu index defined in pyproject.toml)
RUN uv sync --no-dev

# Environment defaults (override with -e or docker-compose)
ENV TTS_LANGUAGE=spanish_24l \
    TTS_DEFAULT_VOICE=lola \
    TTS_QUANTIZE=true \
    HOST=0.0.0.0 \
    PORT=8000

# Pre-download the model weights during build so the container starts immediately.
# Remove this RUN block to keep the image smaller and download on first request.
# If the model requires HF authentication, mount a secret:
#   docker build --secret id=hf_token,env=HF_TOKEN .
# RUN uv run python -c "from pocket_tts import TTSModel; TTSModel.load_model(language='spanish_24l')"

EXPOSE 8000

CMD ["uv", "run", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
