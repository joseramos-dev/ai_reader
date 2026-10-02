package dev.joseramos.aireader.spikes.common

/** Textos originales en español para las pruebas (no proceden de ningún libro). */
object SampleTexts {
    val ttsPhrases = listOf(
        "Capítulo tres. La ciudad despertó bajo una lluvia fina que nadie esperaba.",
        "¿Cuántas veces habría recorrido aquel camino sin fijarse en los detalles?",
        "Según los registros de 1874, el puerto recibía más de doscientos barcos al año.",
        "La profesora sonrió, cerró el cuaderno y dijo: «Mañana empezaremos por el final».",
        "Entre el 12 y el 15 % de la población vivía entonces fuera de las murallas."
    )

    val paragraphs = listOf(
        // 0 · historia
        "Durante el siglo XVIII, las ciudades portuarias del Mediterráneo crecieron gracias al comercio de " +
            "aceite, vino y tejidos. Los mercaderes formaban compañías familiares que repartían el riesgo de cada " +
            "viaje entre varios socios, y los contratos se registraban ante notario con un detalle sorprendente. " +
            "Cuando un barco naufragaba, las pérdidas se dividían según la parte aportada por cada familia, lo que " +
            "permitió que muchas casas comerciales sobrevivieran a varias décadas de guerras y epidemias. Con el " +
            "tiempo, esas prácticas dieron lugar a los primeros seguros marítimos organizados de la región.",
        // 1 · biología
        "La memoria no es un único almacén, sino un conjunto de sistemas que colaboran. La memoria episódica " +
            "guarda acontecimientos concretos, con su lugar y su momento, mientras que la memoria semántica conserva " +
            "conocimientos generales que ya no recordamos cómo aprendimos. El hipocampo resulta esencial para " +
            "consolidar los recuerdos episódicos durante el sueño, y por eso las personas que duermen poco suelen " +
            "recordar peor lo que vivieron el día anterior. Con la repetición, muchos recuerdos episódicos acaban " +
            "transformándose en conocimiento semántico.",
        // 2 · economía
        "La inflación no afecta a todos por igual. Quienes ahorran en efectivo pierden poder adquisitivo con " +
            "rapidez, mientras que quienes tienen deudas a tipo fijo ven cómo su carga real disminuye. Los bancos " +
            "centrales suelen subir los tipos de interés para enfriar la demanda, pero esa medida tarda entre doce y " +
            "dieciocho meses en notarse por completo. Mientras tanto, los salarios acostumbran a ir " +
            "por detrás de los " +
            "precios, de modo que los hogares con menos ingresos sufren la mayor parte del ajuste.",
        // 3 · astronomía
        "Las estrellas de neutrones son los restos de explosiones de supernova. En un objeto de apenas veinte " +
            "kilómetros de diámetro se concentra más masa que la del Sol, de modo que una cucharadita de su material " +
            "pesaría miles de millones de toneladas. Algunas giran cientos de veces por segundo y emiten haces de " +
            "radiación que barren el espacio como un faro; cuando esos haces apuntan hacia la Tierra, los " +
            "radiotelescopios detectan pulsos con una regularidad que rivaliza con la de los mejores relojes atómicos.",
        // 4 · cocina
        "Para una buena masa de pan basta con harina, agua, sal y levadura, pero el tiempo es el ingrediente " +
            "que marca la diferencia. Una fermentación lenta en frío, de entre doce y veinticuatro horas, " +
            "permite que " +
            "las enzimas descompongan parte del almidón en azúcares sencillos. El resultado es una corteza más " +
            "dorada, una miga con alveolos irregulares y un sabor ligeramente ácido. Conviene hornear con vapor " +
            "durante los primeros minutos para que la corteza no se endurezca antes de que el pan termine de crecer.",
        // 5 · literatura
        "En muchas novelas del siglo XIX, el narrador omnisciente conoce los pensamientos de todos los personajes " +
            "y se permite juzgarlos. A comienzos del siglo XX, varios autores abandonaron esa voz y prefirieron " +
            "contar la historia desde la conciencia de un solo personaje, con sus dudas y sus contradicciones. Ese " +
            "cambio obligó al lector a reconstruir por su cuenta lo que ocurría, y convirtió la ambigüedad en un " +
            "recurso deliberado en lugar de un defecto del relato."
    )

    /** Pregunta → índice del párrafo que la responde, para comprobar que la búsqueda tiene sentido. */
    val queries = listOf(
        "¿Qué parte del cerebro ayuda a consolidar los recuerdos mientras dormimos?" to 1,
        "¿Por qué la inflación perjudica más a los hogares con menos ingresos?" to 2,
        "¿Cómo repartían los mercaderes las pérdidas de un naufragio?" to 0,
        "¿Cuánto pesa el material de una estrella de neutrones?" to 3,
        "¿Por qué conviene fermentar la masa de pan en frío?" to 4,
        "¿Qué cambió en la forma de narrar a principios del siglo XX?" to 5
    )

    /** Fragmento de unos 350 tokens: dos párrafos consecutivos. */
    fun chunk(i: Int): String = paragraphs[i % paragraphs.size] + "\n\n" + paragraphs[(i + 1) % paragraphs.size]
}
