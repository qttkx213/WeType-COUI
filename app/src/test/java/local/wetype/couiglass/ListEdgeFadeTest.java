package local.wetype.couiglass;

import org.junit.Test;

import static local.wetype.couiglass.ListEdgeFade.fadeDepth;
import static org.junit.Assert.assertEquals;

public final class ListEdgeFadeTest {
    @Test public void completeRowsAndRowsOutsideTheViewportStayOpaque() {
        assertEquals(0f, fadeDepth(58f, 0f, 16), 0f);
        assertEquals(0f, fadeDepth(58f, -1f, 16), 0f);
        assertEquals(0f, fadeDepth(0f, 58f, 16), 0f);
    }

    @Test public void partialRowsFadeOnlyInTheirVisibleEdge() {
        assertEquals(16f, fadeDepth(40f, 18f, 16), 0f);
        assertEquals(5f, fadeDepth(5f, 53f, 16), 0f);
    }

    @Test public void fadeShrinksAsTheRowBecomesFullyVisible() {
        assertEquals(5f, fadeDepth(53f, 5f, 16), 0f);
        assertEquals(.5f, fadeDepth(57.5f, .5f, 16), 0f);
    }
}
