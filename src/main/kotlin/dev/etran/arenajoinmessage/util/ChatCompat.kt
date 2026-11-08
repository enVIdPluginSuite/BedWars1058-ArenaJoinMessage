package dev.etran.arenajoinmessage.util

import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import java.lang.reflect.Array as JArray

/**
 * Builds clickable / hoverable chat lines with the BungeeCord chat API that ships inside every
 * Spigot and Paper version from 1.8 up to the newest ones.
 *
 * The plugin is compiled against the 1.8 API, but newer versions reshaped the hover event
 * (it takes `Content` objects instead of components), so that one call has a reflective fallback.
 */
object ChatCompat {

    @Volatile
    private var legacyHoverWorks = true

    /** One chat line with [hoverLines] shown on hover and [click] executed on click. */
    fun line(text: String, hoverLines: List<String>, click: ClickEvent?): TextComponent {
        val root = TextComponent()
        val hover = if (hoverLines.isEmpty()) null else hoverEvent(TextComponent.fromLegacyText(hoverLines.joinToString("\n")))
        for (part in TextComponent.fromLegacyText(text)) {
            if (click != null) part.clickEvent = click
            if (hover != null) part.hoverEvent = hover
            root.addExtra(part)
        }
        return root
    }

    private fun hoverEvent(components: Array<BaseComponent>): HoverEvent? {
        if (legacyHoverWorks) {
            try {
                return HoverEvent(HoverEvent.Action.SHOW_TEXT, components)
            } catch (ignored: LinkageError) {
                legacyHoverWorks = false
            }
        }
        return try {
            val contentClass = Class.forName("net.md_5.bungee.api.chat.hover.content.Content")
            val textClass = Class.forName("net.md_5.bungee.api.chat.hover.content.Text")
            val text = textClass.getConstructor(Array<BaseComponent>::class.java).newInstance(components)
            val contents = JArray.newInstance(contentClass, 1)
            JArray.set(contents, 0, text)
            HoverEvent::class.java
                .getConstructor(HoverEvent.Action::class.java, contents.javaClass)
                .newInstance(HoverEvent.Action.SHOW_TEXT, contents)
        } catch (ignored: Throwable) {
            null // no hover text, the click still works
        }
    }
}
