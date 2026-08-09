package com.zhubao.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlashCommandsTest {

    @Test
    void recognizesCommands() {
        assertEquals(SlashCommands.Action.HELP, SlashCommands.parse("/help"));
        assertEquals(SlashCommands.Action.EXIT, SlashCommands.parse("/exit"));
        assertEquals(SlashCommands.Action.EXIT, SlashCommands.parse("/quit"));
        assertEquals(SlashCommands.Action.CLEAR, SlashCommands.parse("/clear"));
        assertEquals(SlashCommands.Action.NEW, SlashCommands.parse("/new"));
        assertEquals(SlashCommands.Action.PERMISSIONS, SlashCommands.parse("/permissions"));
        assertEquals(SlashCommands.Action.PERMISSIONS, SlashCommands.parse("/permissions reset"));
        assertEquals(SlashCommands.Action.PLAN, SlashCommands.parse("/plan"));
        assertEquals(SlashCommands.Action.PLAN, SlashCommands.parse("/plan 重构 xxx"));
        assertEquals(SlashCommands.Action.UNDO, SlashCommands.parse("/undo"));
        assertEquals(SlashCommands.Action.REWIND, SlashCommands.parse("/rewind"));
        assertEquals(SlashCommands.Action.HELP, SlashCommands.parse("  /HELP  "));
    }

    @Test
    void unknownOrPlainTextIsNone() {
        assertEquals(SlashCommands.Action.NONE, SlashCommands.parse("/foo"));
        assertEquals(SlashCommands.Action.NONE, SlashCommands.parse("你好"));
        assertEquals(SlashCommands.Action.NONE, SlashCommands.parse(""));
        assertEquals(SlashCommands.Action.NONE, SlashCommands.parse(null));
    }

    @Test
    void isCommandDetection() {
        assertTrue(SlashCommands.isCommand("/help"));
        assertTrue(SlashCommands.isCommand("/exit"));
        assertTrue(SlashCommands.isCommand("/new"));
        assertTrue(SlashCommands.isCommand("/permissions"));
        assertFalse(SlashCommands.isCommand("/"));
        assertFalse(SlashCommands.isCommand("hello /exit"));
        assertFalse(SlashCommands.isCommand(null));
        assertFalse(SlashCommands.isCommand(""));
    }
}
