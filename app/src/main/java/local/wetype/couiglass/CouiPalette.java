package local.wetype.couiglass;

final class CouiPalette {
    private CouiPalette() {}

    static int panel(boolean dark, boolean blurred) {
        // Match the neutral grays in the reference images on similar app backgrounds.
        if (dark) return blurred ? 0x920E0E0E : 0xFF0F1014;
        return blurred ? 0x88CFCFD3 : 0xFFDDDDDF;
    }

    static int topBarFill(boolean dark, int opacity) {
        // TopAppBar filters composited through the translucent IME window. Dark's nearly neutral
        // overlay is folded into its source-over tint. Light uses the luminosity tint as the fill.
        return fade(dark ? 0xCC262626 : 0x99F5F5F5, opacity);
    }

    static boolean darkKey(boolean darkTheme, int hostFill) {
        if (darkTheme) return true;
        int red = (hostFill >> 16) & 255;
        int green = (hostFill >> 8) & 255;
        int blue = hostFill & 255;
        // WeType's accent keys use light glyphs even in the light keyboard theme.
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) > 40;
    }

    static int pressMask(boolean dark) {
        return dark ? 0x33FFFFFF : 0x1F000000;
    }

    static int fade(int color, int opacity) {
        int alpha = ((color >>> 24) * opacity + 127) / 255;
        return (alpha << 24) | (color & 0xFFFFFF);
    }
}
