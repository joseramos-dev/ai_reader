package dev.joseramos.aireader.ai.embeddings

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import java.io.File
import kotlin.random.Random
import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Compara [UnigramTokenizer] con el tokenizador de Hugging Face (vía DJL, nativo de escritorio) sobre
 * el `tokenizer.json` empaquetado. Si el modelo no se ha descargado (lo hace la compilación de
 * `:app`), la prueba se omite.
 */
class UnigramTokenizerTest {

    @Test
    fun matchesHuggingFaceOnSampleTexts() {
        val samples = listOf(
            "",
            " ",
            "   ",
            "passage: Hola, ¿qué tal? Él comió muchísimas croquetas en Logroño.",
            "query: ¿Quién mató al comendador?",
            "  espacios   repetidos\t tabuladores\ny\r\nsaltos de línea  ",
            "acentos descompuestos: é à ñ ü",
            "ligaduras ﬁ ﬂ y anchos ＡＢＣ１２３ ｶﾀｶﾅ",
            "emojis 👍🏽 👨‍👩‍👧 🇪🇸 y símbolos ™ © ½ ²",
            "中文文本，日本語のテキスト、한국어 텍스트",
            "Русский текст и ελληνικά και العربية و עברית",
            "control\u0000\u0007\u001b chars y ​ sin ancho ­ guion blando",
            "tokens especiales <s> dentro </s> del <pad> texto <unk> y <mask>",
            "<s></s><s>",
            "casi especiales <s y s> y </ s>",
            "Caracteres raros:  ￿ 𝔘𝔫𝔦𝔠𝔬𝔡𝔢 𓀀 🧬",
            "MAYÚSCULAS Y NÚMEROS 1.234,56 € 3,5% año 2026",
            "guiones — largos – y «comillas» “inglesas” ‘simples’…",
            "x".repeat(300),
            "palabra ".repeat(800),
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(60)
        )
        samples.forEach(::assertSame)
    }

    @Test
    fun matchesHuggingFaceOnRandomTexts() {
        val random = Random(42)
        val pools = listOf(
            "abcdefghijklmnopqrstuvwxyzáéíóúñü ABCDEFGHIJKLMNÑOPQRSTUVWXYZ",
            "     \t\n.,;:¡!¿?()[]{}\"'«»—–-_/\\@#\$%&*+=<>|~^`",
            "0123456789½²³€£¥",
            "̧́̀̃̈​‍­️",
            "ﬁﬂＡＢＣａｂｃ１２３ｶﾀｶﾅ",
            "中文日本語한국어Русскийελληνικάالعربيةעברית",
            "<s></s><pad><unk><mask>"
        )
        repeat(RANDOM_CASES) {
            val length = random.nextInt(0, 200)
            val text = buildString {
                repeat(length) {
                    when (random.nextInt(20)) {
                        0 -> appendCodePoint(EMOJIS[random.nextInt(EMOJIS.size)])
                        // Solo caracteres asignados: en los sin asignar, el BreakIterator del JDK y
                        // el de Rust pueden agrupar distinto (dependen de su versión de Unicode).
                        1 -> appendCodePoint(
                            generateSequence { random.nextInt(0x20, 0x3000) }.first { Character.isDefined(it) }
                        )
                        else -> pools[random.nextInt(pools.size)].let { append(it[random.nextInt(it.length)]) }
                    }
                }
            }
            assertSame(text)
        }
    }

    private fun assertSame(text: String) {
        val expected = reference.encode(text).ids
        val actual = tokenizer.encode(text)
        assertArrayEquals("Texto: «$text»", expected, actual)
    }

    companion object {
        private const val MAX_TOKENS = 512
        private const val RANDOM_CASES = 2_000
        private val EMOJIS = intArrayOf(0x1F600, 0x1F44D, 0x1F3FD, 0x1F1EA, 0x1F1F8, 0x1F9EC, 0x2764, 0x1F468)
        private val tokenizerFile =
            File("../../app/src/main/assets/bundled_models/emb-multilingual-e5-small-int8/tokenizer.json")

        private lateinit var tokenizer: UnigramTokenizer
        private lateinit var reference: HuggingFaceTokenizer

        @BeforeClass
        @JvmStatic
        fun load() {
            assumeTrue("Falta $tokenizerFile", tokenizerFile.exists())
            tokenizer = UnigramTokenizer.load(tokenizerFile, MAX_TOKENS)
            reference = HuggingFaceTokenizer.builder()
                .optTokenizerPath(tokenizerFile.toPath())
                .optMaxLength(MAX_TOKENS)
                .optTruncation(true)
                .optPadding(false)
                .build()
        }

        @AfterClass
        @JvmStatic
        fun close() {
            if (::reference.isInitialized) reference.close()
        }
    }
}
