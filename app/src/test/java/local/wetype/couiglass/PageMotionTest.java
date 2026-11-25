package local.wetype.couiglass;

import org.junit.Test;

import static local.wetype.couiglass.PageMotion.Page.named;
import static local.wetype.couiglass.PageMotion.animateTools;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PageMotionTest {
    @Test public void toolsAnimateWhenOpenedFromOrClosedIntoTheKeyboard() {
        for (String keyboard : new String[]{"ChineseQwerty", "ChineseT9", "EnglishQwerty", "Number"}) {
            assertTrue(keyboard, animateTools(named(keyboard), named("Setting"), false));
            assertTrue(keyboard, animateTools(named("Setting"), named(keyboard), false));
        }
    }

    @Test public void keyboardLayoutsAndLanguageChangesAreImmediate() {
        assertImmediate("ChineseQwerty", "EnglishQwerty");
        assertImmediate("ChineseQwerty", "ChineseNumberSymbols");
        assertImmediate("ChineseNumberSymbols", "Symbols");
        assertImmediate("ChineseNumberSymbols", "Number");
    }

    @Test public void panelsAreImmediateFromTheKeyboardAndTheToolsGrid() {
        for (String panel : new String[]{"Emoji", "CustomPhraseAndClipboard", "KeyboardChoose", "SymbolsView",
                "AlternativeWords", "HandwriteFindWordT26", "ClipboardCleanRecord", "NetworkSetting"}) {
            assertImmediate("ChineseQwerty", panel);
            assertImmediate("Setting", panel);
        }
    }

    @Test public void openingOrLeavingAToolSubpageIsImmediate() {
        assertFalse(animateTools(named("ChineseQwerty"), named("Setting"), true));
        assertFalse(animateTools(named("Setting"), named("ChineseQwerty"), true));
        assertFalse(animateTools(named("Setting"), named("Setting"), false));
    }

    private static void assertImmediate(String first, String second) {
        assertFalse(first + " -> " + second, animateTools(named(first), named(second), false));
        assertFalse(second + " -> " + first, animateTools(named(second), named(first), false));
    }
}
