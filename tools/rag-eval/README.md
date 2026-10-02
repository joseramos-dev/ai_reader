# Evaluación de la búsqueda del RAG

Mide si la búsqueda del chat encuentra la página donde está la respuesta (F7). Se ejecuta en el
móvil, sobre los libros ya importados e indexados:

1. Instala una compilación de depuración y descarga en Ajustes el «Modelo para preguntar al libro».
2. Importa los libros del fichero de preguntas y espera a que estén listos.
3. Copia el JSON al móvil y ábrelo desde Ajustes → Acerca de → «Evaluar la búsqueda».

## Formato

```json
{
  "books": [
    {
      "title": "Crimen y castigo",
      "questions": [
        { "question": "¿Dónde vive Raskólnikov al principio?", "pages": [5, 6] }
      ]
    }
  ]
}
```

- `title`: basta con una parte del título tal como aparece en la Biblioteca.
- `pages`: páginas del PDF (no las impresas en el libro) donde está la respuesta. Una pregunta cuenta
  como resuelta en el puesto del primer fragmento que toque alguna de ellas.

## Cómo escribir las preguntas

Unas 20 preguntas sobre 2–3 libros de tipos distintos (una novela, un ensayo o libro técnico y un
manual), mezclando:

- Hechos concretos con nombres propios o cifras (donde ayuda la búsqueda de texto).
- Preguntas parafraseadas, sin las palabras exactas del libro (donde ayudan los embeddings).
- Alguna pregunta cuya respuesta está repartida en dos páginas.

Las preguntas globales («¿de qué trata el libro?») no sirven aquí: el chat las responde con los
resúmenes, no con la búsqueda.

## Cómo leer el resultado

Para la búsqueda vectorial, la de texto completo y la fusión (la que usa el chat) se muestran:

- **R@k**: porcentaje de preguntas resueltas entre los k primeros fragmentos. El chat envía 8, así
  que el objetivo del plan es **R@8 ≥ 80 %** en la fusión.
- **MRR**: media de 1/puesto; cuanto más cerca de 1, antes aparece la respuesta.

Debajo salen las preguntas que la fusión no resuelve entre los 8 primeros, con el puesto que les dio
cada búsqueda, para ajustar los parámetros (`CANDIDATES` y `DEFAULT_K` en `HybridRetriever`, `k` de
la fusión RRF y el tamaño de los fragmentos en `Chunker`).

`plantilla.json` es un punto de partida: cambia los títulos, las preguntas y las páginas por las de
tus libros.
