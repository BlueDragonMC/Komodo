package com.bluedragonmc.komodo

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentIteratorType
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * Centers the component.
 * If it isn't working properly, you may need to prefix the component with `Component.empty()`, for some reason.
 * @param centerPx The number of pixels from the left that the center is located at.
 */
fun Component.center(centerPx: Int = 154): Component {
    var componentSizePx = 0
    for (i in this.iterateChildren()) {
        val isBold = i.style().hasDecoration(TextDecoration.BOLD)
        for (c in i.toPlainText()) {
            componentSizePx +=
                if (isBold) DefaultFontInfo.getBoldLength(c)
                else DefaultFontInfo.getLength(c)
        }
    }
    val halvedSize = componentSizePx / 2
    val toCompensate = centerPx - halvedSize
    val spaceLength = DefaultFontInfo.getLength(' ') + 1
    var compensated = 0
    val sb = StringBuilder()
    while (compensated < toCompensate) {
        sb.append(" ")
        compensated += spaceLength
    }
    return Component.text(sb.toString()) + this
}

fun Component.iterateChildren(): List<Component> {
    val list = mutableListOf<Component>()
    this.iterator(ComponentIteratorType.DEPTH_FIRST).forEachRemaining {
        list.add(it.children(emptyList()))
    }
    return list
}

fun Component.toPlainText() = PlainTextComponentSerializer.plainText().serialize(this)
operator fun Component.plus(component: Component) = append(component)

/**
 * Adapted from https://www.spigotmc.org/threads/free-code-sending-perfectly-centered-chat-message.95872/
 */
private object DefaultFontInfo {

    private val lengths = mapOf(
        'A' to 5,
        'a' to 5,
        'B' to 5,
        'b' to 5,
        'C' to 5,
        'c' to 5,
        'D' to 5,
        'd' to 5,
        'E' to 5,
        'e' to 5,
        'F' to 5,
        'f' to 4,
        'G' to 5,
        'g' to 5,
        'H' to 5,
        'h' to 5,
        'I' to 3,
        'i' to 1,
        'J' to 5,
        'j' to 5,
        'K' to 5,
        'k' to 4,
        'L' to 5,
        'l' to 1,
        'M' to 5,
        'm' to 5,
        'N' to 5,
        'n' to 5,
        'O' to 5,
        'o' to 5,
        'P' to 5,
        'p' to 5,
        'Q' to 5,
        'q' to 5,
        'R' to 5,
        'r' to 5,
        'S' to 5,
        's' to 5,
        'T' to 5,
        't' to 4,
        'U' to 5,
        'u' to 5,
        'V' to 5,
        'v' to 5,
        'W' to 5,
        'w' to 5,
        'X' to 5,
        'x' to 5,
        'Y' to 5,
        'y' to 5,
        'Z' to 5,
        'z' to 5,
        '1' to 5,
        '2' to 5,
        '3' to 5,
        '4' to 5,
        '5' to 5,
        '6' to 5,
        '7' to 5,
        '8' to 5,
        '9' to 5,
        '0' to 5,
        '!' to 1,
        '@' to 6,
        '#' to 5,
        '$' to 5,
        '%' to 5,
        '^' to 5,
        '&' to 5,
        '*' to 5,
        '(' to 4,
        ')' to 4,
        '-' to 5,
        '_' to 5,
        '+' to 5,
        '=' to 5,
        '{' to 4,
        '}' to 4,
        '[' to 3,
        ']' to 3,
        ':' to 1,
        ';' to 1,
        '"' to 3,
        '\'' to 1,
        '<' to 4,
        '>' to 4,
        '?' to 5,
        '/' to 5,
        '\\' to 5,
        '|' to 1,
        '~' to 5,
        '`' to 2,
        '.' to 1,
        ',' to 1,
        ' ' to 2,
        'a' to 4
    )

    private const val DEFAULT_LENGTH = 4

    fun getBoldLength(c: Char): Int = if (c == ' ') getLength(c) else (getLength(c) * 1.1555555555555557).toInt()

    fun getLength(c: Char) = lengths[c] ?: DEFAULT_LENGTH
}
