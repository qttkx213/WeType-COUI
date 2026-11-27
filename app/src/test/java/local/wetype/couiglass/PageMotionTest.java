package local.wetype.couiglass;

import org.junit.Test;

import static local.wetype.couiglass.PageMotion.Direction.CLOSE;
import static local.wetype.couiglass.PageMotion.Direction.NONE;
import static local.wetype.couiglass.PageMotion.Direction.OPEN;
import static local.wetype.couiglass.PageMotion.Page.named;
import static local.wetype.couiglass.PageMotion.Source.BACK;
import static local.wetype.couiglass.PageMotion.Source.OTHER;
import static local.wetype.couiglass.PageMotion.Source.TOOLBAR;
import static local.wetype.couiglass.PageMotion.inlineMotion;
import static local.wetype.couiglass.PageMotion.inlinePanel;
import static local.wetype.couiglass.PageMotion.snapshotFit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

public class PageMotionTest {
    private final PageMotion.ToolNavigation navigation = new PageMotion.ToolNavigation();

    @Test public void toolsGridAndItsPagesOpenAndReturn() {
        route(OPEN, 1, "ChineseQwerty", 201, "Setting", TOOLBAR);
        route(OPEN, 201, "Setting", 501, "CustomPhraseAndClipboard", TOOLBAR);
        route(CLOSE, 501, "CustomPhraseAndClipboard", 201, "Setting", BACK);
        route(CLOSE, 201, "Setting", 1, "ChineseQwerty", BACK);
    }

    @Test public void topToolbarShortcutsOpenAndCanReturnDirectlyToTyping() {
        route(OPEN, 1, "ChineseQwerty", 501, "CustomPhraseAndClipboard", TOOLBAR);
        route(CLOSE, 501, "CustomPhraseAndClipboard", 1, "ChineseQwerty", BACK);
        route(OPEN, 1, "ChineseQwerty", 506, "KeyboardChoose", TOOLBAR);
        route(CLOSE, 506, "KeyboardChoose", 1, "ChineseQwerty", BACK);
        route(OPEN, 1, "ChineseQwerty", 201, "Setting", TOOLBAR);
        route(OPEN, 201, "Setting", 504, "Emoji", TOOLBAR);
        route(CLOSE, 504, "Emoji", 1, "ChineseQwerty", BACK);
    }

    @Test public void toolbarChooserHasDirectAndDelayedInlineEntries() {
        assertEquals(KeyboardStyle.INLINE_CHOOSER, inlinePanel(5));
        assertEquals(KeyboardStyle.INLINE_CHOOSER, inlinePanel(1));
        assertNull(inlinePanel(-1));
        assertEquals(OPEN, inlineMotion(true, true, false));
        assertEquals(OPEN, inlineMotion(true, false, true));
        assertEquals(CLOSE, inlineMotion(false, false, true));
        assertEquals(NONE, inlineMotion(true, false, false));
        assertEquals(NONE, inlineMotion(false, false, false));
    }

    @Test public void aDeferredToolbarBackAppliesOnlyToTheTrackedCorrectionPage() {
        route(OPEN, 1, "ChineseQwerty", 201, "Setting", TOOLBAR);
        route(OPEN, 201, "Setting", 152, "Correction", TOOLBAR);
        navigation.onCandidateBack();
        route(CLOSE, 152, "Correction", 1, "ChineseQwerty", OTHER);
        route(NONE, 1, "ChineseQwerty", 152, "Correction", OTHER);
        navigation.onCandidateBack();
        route(NONE, 152, "Correction", 1, "ChineseQwerty", OTHER);
    }

    @Test public void theSamePagesCanOpenAndCloseWithoutAToolbarClick() {
        route(NONE, 1, "ChineseQwerty", 504, "Emoji", OTHER);
        route(NONE, 504, "Emoji", 1, "ChineseQwerty", BACK);
        route(NONE, 1, "ChineseQwerty", 501, "CustomPhraseAndClipboard", OTHER);
        route(NONE, 501, "CustomPhraseAndClipboard", 1, "ChineseQwerty", BACK);
        route(NONE, 1, "ChineseQwerty", 506, "KeyboardChoose", OTHER);
        route(NONE, 506, "KeyboardChoose", 1, "ChineseQwerty", BACK);
        route(NONE, 1, "ChineseQwerty", 201, "Setting", OTHER);
        route(NONE, 201, "Setting", 1, "ChineseQwerty", BACK);
    }

    @Test public void candidatesAndKeyLayoutChangesStayImmediate() {
        route(NONE, 1, "ChineseQwerty", 100, "EnglishQwerty", OTHER);
        route(NONE, 100, "EnglishQwerty", 102, "EnglishNumberSymbols", OTHER);
        route(NONE, 102, "EnglishNumberSymbols", 103, "ChineseNumberSymbols", OTHER);
        route(NONE, 103, "ChineseNumberSymbols", 502, "SymbolsView", OTHER);
        route(NONE, 1, "ChineseQwerty", 106, "AlternativeWords", OTHER);
        route(NONE, 106, "AlternativeWords", 1, "ChineseQwerty", BACK);
        route(NONE, 1, "ChineseQwerty", 1, "ChineseQwerty", OTHER);
        route(OPEN, 1, "ChineseQwerty", 201, "Setting", TOOLBAR);
        route(NONE, 201, "Setting", 2, "HandWrite", OTHER);
    }

    @Test public void handwriteToolAnimatesOnlyFromTheToolbarInBothLayouts() {
        String[] pages = {"HandwriteFindWordT9", "HandwriteFindWordT26"};
        for (int i = 0; i < pages.length; i++) {
            int handwrite = 107 + i;
            route(NONE, 1, "ChineseQwerty", handwrite, pages[i], OTHER);
            route(NONE, handwrite, pages[i], 1, "ChineseQwerty", BACK);
            route(OPEN, 1, "ChineseQwerty", 201, "Setting", TOOLBAR);
            route(NONE, 201, "Setting", 1, "ChineseQwerty", TOOLBAR);
            route(OPEN, 1, "ChineseQwerty", handwrite, pages[i], TOOLBAR);
            route(CLOSE, handwrite, pages[i], 1, "ChineseQwerty", BACK);
        }
    }

    @Test public void clipboardPreviewIconOpensThePanelAndOtherPreviewActionsStayImmediate() {
        route(OPEN, 1, "ChineseQwerty", 501, "CustomPhraseAndClipboard", TOOLBAR);
        route(CLOSE, 501, "CustomPhraseAndClipboard", 1, "ChineseQwerty", BACK);
        route(NONE, 1, "ChineseQwerty", 1, "ChineseQwerty", OTHER);
        route(NONE, 1, "ChineseQwerty", 509, "ImagePreview", OTHER);
        route(NONE, 509, "ImagePreview", 1, "ChineseQwerty", BACK);
        route(OPEN, 1, "ChineseQwerty", 509, "ImagePreview", TOOLBAR);
        route(CLOSE, 509, "ImagePreview", 1, "ChineseQwerty", BACK);
        route(NONE, 1, "ChineseQwerty", 510, "ClipboardBomb", OTHER);
    }

    @Test public void clipboardHistoryAndInternalActionsStayImmediate() {
        route(OPEN, 1, "ChineseQwerty", 501, "CustomPhraseAndClipboard", TOOLBAR);
        route(NONE, 501, "CustomPhraseAndClipboard", 603, "ClipboardCleanRecord", OTHER);
        route(NONE, 603, "ClipboardCleanRecord", 501, "CustomPhraseAndClipboard", BACK);
        route(CLOSE, 501, "CustomPhraseAndClipboard", 1, "ChineseQwerty", BACK);
        route(OPEN, 1, "ChineseQwerty", 501, "CustomPhraseAndClipboard", TOOLBAR);
        route(NONE, 501, "CustomPhraseAndClipboard", 1, "ChineseQwerty", OTHER);
    }

    @Test public void toolGrowsDownFromTheTopAndContractsBackToIt() {
        assertEquals(.96f, OPEN.pageScale(0f), 0f);
        assertEquals(.98f, OPEN.pageScale(.5f), 0f);
        assertEquals(1f, OPEN.pageScale(1f), 0f);
        assertEquals(1f, CLOSE.pageScale(0f), 0f);
        assertEquals(.98f, CLOSE.pageScale(.5f), 0f);
        assertEquals(.96f, CLOSE.pageScale(1f), 0f);
    }

    @Test public void resizeFitsTheWholeOutgoingFrameWithoutStretchingShorterFrames() {
        assertEquals(800f, 920f * snapshotFit(920, 800), .001f);
        assertEquals(1f, snapshotFit(920, 920), 0f);
        assertEquals(1f, snapshotFit(800, 920), 0f);
    }

    @Test public void pagesOverlapAtTheHandoffInBothDirections() {
        for (PageMotion.Direction direction : new PageMotion.Direction[]{OPEN, CLOSE}) {
            float start = direction.fadeStart();
            float end = direction.fadeEnd();
            assertEquals(0, direction.incomingAlpha(0f));
            assertEquals(0, direction.incomingAlpha(start));
            int incoming = direction.incomingAlpha((start + end) / 2f);
            assertTrue(incoming >= 127 && incoming <= 128);
            assertEquals(255, direction.incomingAlpha(end));
            assertEquals(255, direction.incomingAlpha(1f));
        }
    }

    private void route(PageMotion.Direction expected, int fromId, String from, int toId, String to,
                       PageMotion.Source source) {
        assertEquals(from + " -> " + to + " via " + source, expected,
                navigation.change(fromId, named(from), toId, named(to), source));
    }
}
