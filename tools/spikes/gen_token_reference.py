"""Genera los tokens de referencia para el spike 3 (tokenizador en Kotlin).

Descarga el tokenizer.json de cada modelo de embeddings candidato, codifica un
conjunto fijo de frases con la librería oficial `tokenizers` de Hugging Face y
guarda los ids en spikes/src/main/assets/token_reference.json. La app de spikes
compara después esos ids con los que produce el tokenizador en Android.

Uso:
    pip install tokenizers
    python tools/spikes/gen_token_reference.py
"""

import json
import urllib.request
from pathlib import Path

from tokenizers import Tokenizer, __version__ as tokenizers_version

MODELS = {
    "e5-small": "https://huggingface.co/Xenova/multilingual-e5-small/resolve/main/tokenizer.json",
    "embeddinggemma": "https://huggingface.co/onnx-community/embeddinggemma-300m-ONNX/resolve/main/tokenizer.json",
}

# Frases originales con los casos difíciles habituales en libros en español.
SENTENCES = [
    "El capítulo tercero comienza con una pregunta: ¿qué es lo que realmente sabemos?",
    "Según el autor, la economía «circular» redujo un 37,5 % los residuos entre 1998 y 2004.",
    "Ñandúes, pingüinos y cigüeñas atravesaron el río Guadalquivir al amanecer.",
    "La ﬁgura 2.3 (pág. 114) muestra el pseudocódigo del algoritmo O(n log n).",
    "Véase https://ejemplo.org/libro?id=42 y el correo autor@ejemplo.org.",
    "Texto con guion de corte de pa-\nlabra y saltos\tde línea.",
    "Mezcla de idiomas: the quick brown fox, l'été français, 東京.",
    "   espacios    múltiples   y emojis 📚✨ al final   ",
    "query: ¿Qué diferencia hay entre memoria episódica y semántica?",
    "task: search result | query: ¿Cuándo se firmó el tratado?",
    "title: none | text: El tratado se firmó en 1648 tras treinta años de guerra.",
    "I. INTRODUCCIÓN — «Nadie lee dos veces el mismo libro», escribió alguien.",
]

OUT = Path(__file__).resolve().parents[2] / "spikes" / "src" / "main" / "assets" / "token_reference.json"


def main() -> None:
    result = {"tokenizers_version": tokenizers_version, "models": {}}
    for name, url in MODELS.items():
        print(f"Descargando {name}…")
        with urllib.request.urlopen(url) as response:
            tokenizer = Tokenizer.from_str(response.read().decode("utf-8"))
        result["models"][name] = [
            {"text": text, "ids": tokenizer.encode(text, add_special_tokens=True).ids}
            for text in SENTENCES
        ]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"Escrito {OUT}")


if __name__ == "__main__":
    main()
