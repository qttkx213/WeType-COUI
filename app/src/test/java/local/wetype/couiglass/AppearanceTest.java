package local.wetype.couiglass;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class AppearanceTest {
    @Test public void usesNeutralPanelColorsWhenBlurIsUnavailable() {
        assertEquals(0xFFE3E5E9, CouiPalette.panel(false, false));
        assertEquals(0xBDD9DCE1, CouiPalette.panel(false, true));
        assertEquals(0xFF1C1C1E, CouiPalette.panel(true, false));
        assertEquals(0xA81C1C1E, CouiPalette.panel(true, true));
        for (boolean dark : new boolean[]{true, false}) {
            assertTrue(alpha(CouiPalette.panel(dark, true)) < alpha(CouiPalette.panel(dark, false)));
        }
    }

    @Test public void keepsTheToolbarFillAndPressColors() {
        assertEquals(0x99F5F5F5, CouiPalette.topBarFill(false, 255));
        assertEquals(0xCC262626, CouiPalette.topBarFill(true, 255));
        assertEquals(0, alpha(CouiPalette.topBarFill(true, 0)));
        assertEquals(0x1F000000, CouiPalette.pressMask(false));
        assertEquals(0x33FFFFFF, CouiPalette.pressMask(true));
    }

    @Test public void givesLightActionGlyphsTheDarkToolbarFill() {
        assertTrue(CouiPalette.darkKey(false, 0xFF07C160));
        assertTrue(CouiPalette.darkKey(true, 0xFF545454));
        assertEquals(false, CouiPalette.darkKey(false, 0xFFF5F5F5));
    }

    @Test public void fitsRoundedRectanglesWithinKeyBounds() {
        assertEquals(25.2f, KeySurface.radius(90f, 135f, 3f), .01f);
        assertEquals(30f, KeySurface.radius(335f, 130f, 3f), .01f);
        assertTrue(KeySurface.radius(90f, 90f, 3f) < 45f);
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }
}
