package dev.etran.arenajoinmessage.util

import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import java.lang.reflect.Constructor
import java.lang.reflect.Array as JArray

/**
 * Builds clickable / hoverable chat lines with the BungeeCord chat API that ships inside every
 * Spigot and Paper version from 1.8 up to the newest ones.
 *
 * The plugin is compiled against the old API, but newer versions reshaped it (hover events take
 * `Content` objects, click events got factory methods), so those calls have reflective fallbacks.
 */
object ChatCompat {

    /** The modern (1.16+) hover event constructor, or null on servers that only have the old one. */
    private class ContentHover(
        private val textConstructor: Constructor<*>,
        private val hoverConstructor: Constructor<*>,
        private val contentClass: Class<*>,
    ) {
        fun create(components: Array<BaseComponent>): HoverEvent {
            val contents = JArray.newInstance(contentClass, 1)
            JArray.set(contents, 0, textConstructor.newInstance(components))
            return hoverConstructor.newInstance(HoverEvent.Action.SHOW_TEXT, contents) as HoverEvent
        }
    }

    private val contentHover: ContentHover? by lazy {
        try {
            val contentClass = Class.forName("net.md_5.bungee.api.chat.hover.content.Content")
            val textClass = Class.forName("net.md_5.bungee.api.chat.hover.content.Text")
            val textConstructor = textClass.getConstructor(Array<BaseComponent>::class.java)
            val contentArrayClass = JArray.newInstance(contentClass, 0).javaClass
            val hoverConstructor = HoverEvent::class.java.getConstructor(HoverEvent.Action::class.java, contentArrayClass)
            ContentHover(textConstructor, hoverConstructor, contentClass)
        } catch (ignored: Throwable) {
            null
        }
    }

    @Volatile
    private var legacyHoverWorks = true

    /** One chat line with [hoverLines] shown on hover and [click] executed on click. */
    fun line(text: String, hoverLines: List<String>, click: ClickEvent?): TextComponent {
        // TextComponent(String) exists in every bungee-chat version; the no-arg constructor is not guaranteed on 1.8.
        val root = TextComponent("")
        val hover = if (hoverLines.isEmpty()) null else hoverEvent(TextComponent.fromLegacyText(hoverLines.joinToString("\n")))
        for (part in TextComponent.fromLegacyText(text)) {
            // Set explicitly on every part: this also replaces the automatic link a URL in the text would get.
            if (click != null) part.clickEvent = click
            if (hover != null) part.hoverEvent = hover
            root.addExtra(part)
        }
        return root
    }

    /** A click event, or null if this server version offers no way to create one. */
    fun click(action: ClickEvent.Action, value: String): ClickEvent? {
        try {
            return ClickEvent(action, value)
        } catch (ignored: LinkageError) {
            // constructor removed in a newer version, use the factory methods below
        }
        return try {
            val factory = if (action == ClickEvent.Action.SUGGEST_COMMAND) "suggestCommand" else "runCommand"
            ClickEvent::class.java.getMethod(factory, String::class.java).invoke(null, value) as? ClickEvent
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun hoverEvent(components: Array<BaseComponent>): HoverEvent? {
        try {
            contentHover?.let { return it.create(components) }
        } catch (ignored: Throwable) {
            // fall through to the old constructor
        }
        if (legacyHoverWorks) {
            try {
                return HoverEvent(HoverEvent.Action.SHOW_TEXT, components)
            } catch (ignored: LinkageError) {
                legacyHoverWorks = false
            }
        }
        return null // no hover text, the click still works
    }
}
