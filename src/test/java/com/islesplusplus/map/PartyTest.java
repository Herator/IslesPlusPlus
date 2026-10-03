package com.islesplusplus.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PartyTest {
    @Test
    void testMemberName() {
        assertEquals("CreatorWodash", Party.memberName("P » CreatorWodash"));
        assertEquals("chrrisk", Party.memberName("P » [unknown player head] chrrisk ❤126"));
        assertNull(Party.memberName("P »"));
        assertNull(Party.memberName("Players: 12"));
    }
}
