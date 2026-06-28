package io.github.damian1000.orderbook.web

import java.nio.charset.StandardCharsets

/**
 * The static front end (index.html, app.css, app.js), read once from the classpath at startup.
 *
 * The page itself lives under `src/main/resources/web` — proper HTML/CSS/JS files, formatted and
 * linted in CI — rather than embedded in Kotlin string literals.
 */
class WebAssets private constructor(
    val indexHtml: String,
    val appCss: String,
    val appJs: String,
) {
    companion object {
        fun load(): WebAssets = WebAssets(read("/web/index.html"), read("/web/app.css"), read("/web/app.js"))

        private fun read(path: String): String =
            (WebAssets::class.java.getResourceAsStream(path) ?: error("missing resource: $path"))
                .use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }
}
