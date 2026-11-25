package dev.sillport.matepad

import android.view.KeyEvent

/** Android key codes → USB HID usages (the iOS client's UIKeyboardHIDUsage), and modifier bits. */
object Keys {
    // UIKeyModifierFlags bit positions, the same as CGEventFlags for these four.
    const val SHIFT = 1L shl 17
    const val CONTROL = 1L shl 18
    const val OPTION = 1L shl 19
    const val COMMAND = 1L shl 20

    fun modifiers(e: KeyEvent): Long {
        var m = 0L
        if (e.isShiftPressed) m = m or SHIFT
        if (e.isCtrlPressed) m = m or CONTROL
        if (e.isAltPressed) m = m or OPTION
        if (e.isMetaPressed) m = m or COMMAND
        return m
    }

    fun hid(keyCode: Int): Int? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x04 + (keyCode - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x1E + (keyCode - KeyEvent.KEYCODE_1)
        KeyEvent.KEYCODE_0 -> 0x27
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x28
        KeyEvent.KEYCODE_ESCAPE -> 0x29
        KeyEvent.KEYCODE_DEL -> 0x2A
        KeyEvent.KEYCODE_TAB -> 0x2B
        KeyEvent.KEYCODE_SPACE -> 0x2C
        KeyEvent.KEYCODE_MINUS -> 0x2D
        KeyEvent.KEYCODE_EQUALS -> 0x2E
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x2F
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x30
        KeyEvent.KEYCODE_BACKSLASH -> 0x31
        KeyEvent.KEYCODE_SEMICOLON -> 0x33
        KeyEvent.KEYCODE_APOSTROPHE -> 0x34
        KeyEvent.KEYCODE_GRAVE -> 0x35
        KeyEvent.KEYCODE_COMMA -> 0x36
        KeyEvent.KEYCODE_PERIOD -> 0x37
        KeyEvent.KEYCODE_SLASH -> 0x38
        KeyEvent.KEYCODE_CAPS_LOCK -> 0x39
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0x3A + (keyCode - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_MOVE_HOME -> 0x4A
        KeyEvent.KEYCODE_PAGE_UP -> 0x4B
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x4C
        KeyEvent.KEYCODE_MOVE_END -> 0x4D
        KeyEvent.KEYCODE_PAGE_DOWN -> 0x4E
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x4F
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x50
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x51
        KeyEvent.KEYCODE_DPAD_UP -> 0x52
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xE0
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xE1
        KeyEvent.KEYCODE_ALT_LEFT -> 0xE2
        KeyEvent.KEYCODE_META_LEFT -> 0xE3
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xE4
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xE5
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xE6
        KeyEvent.KEYCODE_META_RIGHT -> 0xE7
        else -> null
    }
}
