package local.wetype.couiglass;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class AlphaKeyLabelsTest {
    private static final String KEYS = "com.tencent.wetype.plugin.hld.keyboard.selfdraw.";

    @Test public void displaysLowercaseUntilExplicitShiftOrCapsLock() {
        char q = AlphaKeyLabels.keyLetter(AlphaKeyLabels.keyPrefix(KEYS + "S2ChineseQwertyKeyboard"), "S2_key_q");
        char a = AlphaKeyLabels.keyLetter(AlphaKeyLabels.keyPrefix(KEYS + "S3EnglishQwertyKeyboard"), "S3_key_a");
        assertEquals('q', q);
        assertEquals('a', a);
        assertEquals("q", AlphaKeyLabels.display("Q", q, 1, false));
        assertEquals("a", AlphaKeyLabels.display("A", a, 2, false));
        assertEquals("A", AlphaKeyLabels.display("a", a, 2, true));
        assertEquals("Q", AlphaKeyLabels.display("q", q, 3, false));
        assertEquals("A", AlphaKeyLabels.display("a", a, 4, false));
        assertEquals("q", AlphaKeyLabels.display("Q", q, 5, true));
        assertEquals("Q", AlphaKeyLabels.display("Q", q, 0, false));
    }

    @Test public void leavesReusedIdsAndNonLetterLabelsAlone() {
        assertEquals('a', AlphaKeyLabels.keyLetter(AlphaKeyLabels.keyPrefix(KEYS + "S9DoublePinQwertyKeyboard"), "S2_key_a"));
        assertEquals('a', AlphaKeyLabels.keyLetter(AlphaKeyLabels.keyPrefix(KEYS + "S17WuBiQwertyKeyboard"), "S2_key_a"));
        for (String layout : new String[]{"S28HandwriteT26Keyboard", "S1ChineseT9Keyboard"}) {
            assertEquals(0, AlphaKeyLabels.keyLetter(AlphaKeyLabels.keyPrefix(KEYS + layout), "S2_key_a"));
        }
        String pinyin = AlphaKeyLabels.keyPrefix(KEYS + "S2ChineseQwertyKeyboard");
        for (String id : new String[]{"S1_key_2", "S2_key_1", "S2_key_aa", "S3_key_a", "shift", "delete"}) {
            assertEquals(0, AlphaKeyLabels.keyLetter(pinyin, id));
        }
        assertEquals("ABC", AlphaKeyLabels.display("ABC", 'a', 1, false));
        assertEquals("'词", AlphaKeyLabels.display("'词", 'a', 1, false));
        assertEquals("B", AlphaKeyLabels.display("B", 'a', 1, false));
    }
}
