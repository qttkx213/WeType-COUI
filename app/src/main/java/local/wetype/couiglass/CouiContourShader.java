/* SPDX-License-Identifier: MPL-2.0
 * Floating contour port from COUI-Kit 0.0.1-alpha12.
 * See THIRD_PARTY_NOTICES.md for the upstream revision and license.
 */
package local.wetype.couiglass;

import android.annotation.TargetApi;
import android.graphics.Color;
import android.graphics.RuntimeShader;

@TargetApi(33)
final class CouiContourShader {
    private CouiContourShader() {}

    static RuntimeShader create(float width, float height, float radius, float density,
                                int fill, boolean dark) {
        RuntimeShader shader = new RuntimeShader(SOURCE);
        float x = width / 2f;
        float y = height / 2f;
        boolean circle = width == height && radius >= width / 2f;
        float edgeAlpha = dark ? .60f : .20f;
        float glowAlpha = circle ? (dark ? .10f : .15f) : (dark ? .01f : .10f);
        float fadeScale = !circle && !dark ? 1.15f : 1f;
        float[] glow = innerGlow(x, y, radius, glowAlpha, circle, fadeScale);
        float[] shadowShape = new float[24];
        System.arraycopy(shape(glow[16], glow[17], glow[18]), 0, shadowShape, 0, 12);
        System.arraycopy(shape(x, y, glow[20]), 0, shadowShape, 12, 12);

        shader.setFloatUniform("uSurfaceColor", Color.red(fill) / 255f, Color.green(fill) / 255f,
                Color.blue(fill) / 255f, Color.alpha(fill) / 255f);
        shader.setFloatUniform("uEffectAlpha", 1f);
        shader.setFloatUniform("u_commonArray", new float[]{x, y, 0f, 0f, x, y, radius, CouiCorners.WEIGHT});
        shader.setFloatUniform("u_baseShapeCtxArray", shape(x, y, radius));
        shader.setFloatUniform("u_edgeArray", edge(x, y, (circle ? 1.9f : 2.2f) * density, edgeAlpha, circle));
        shader.setFloatUniform("u_shadowArray", glow);
        shader.setFloatUniform("u_shadowShapeCtxArray", shadowShape);
        return shader;
    }

    private static float[] shape(float x, float y, float radius) {
        float smaller = Math.min(x, y);
        float larger = Math.max(x, y);
        float band = smaller / 80f * 70f;
        return new float[]{x, y, CouiCorners.WEIGHT, radius, x < y ? 1f : 0f, larger, smaller, larger - smaller,
                smaller / 80f * 26.6f, band, smaller / 80f * 60f, Math.max(band, 4f)};
    }

    private static float[] edge(float x, float y, float width, float alpha, boolean circle) {
        float halfPerimeter = Math.max(2f * (x + y), 1f);
        float phase = (x / halfPerimeter * .5f + .5f) % 1f;
        float refWidth = circle ? 160f : 400f;
        float refHeight = circle ? 160f : 100f;
        float[] near = fade(2f * x, 2f * y, circle ? .06f : .2f, circle ? .4f : .35f, refWidth, refHeight);
        float[] far = fade(2f * x, 2f * y, circle ? .01f : .15f, circle ? .28f : .35f, refWidth, refHeight);
        float start = width * (circle ? .25f : .2f);
        return new float[]{1f, 1f, 1f, start, width, start, width * (circle ? .75f : .5f),
                .7f * alpha, alpha, 0f, 0f, 0f, 0f, 0f, circle ? 3f : 4f, .8f,
                halfPerimeter, phase, halfPerimeter * Math.min(phase, 1f - phase) * 2f,
                phase > .5f ? -1f : 1f, near[1] * halfPerimeter, near[0] * halfPerimeter,
                far[1] * halfPerimeter, far[0] * halfPerimeter, -y, x, halfPerimeter * 2f, width};
    }

    private static float[] innerGlow(float x, float y, float radius, float alpha, boolean circle,
                                     float fadeScale) {
        float fadeIn = (circle ? 140f : 120f) * fadeScale;
        float fadeOut = 20f * fadeScale;
        float scaleX = 1f + (circle ? 64f : 120f) / Math.max(2f * x, 1f);
        float scaleY = 1f + (circle ? 56f : 15f) / Math.max(2f * y, 1f);
        float fx = x * scaleX;
        float fy = y * scaleY;
        float small = Math.min(x, y);
        float fadeCorner = Math.min(Math.max(fadeIn * 1.2f, radius), Math.min(fx, fy));
        float outerCorner = Math.min(Math.max(radius, fadeOut * 1.2f), small);
        return new float[]{1f, 1f, 1f, (circle ? .4f : .5f) * alpha, 0f, 0f, fadeIn, 0f, 20f,
                scaleX, scaleY, 4f, .9f, fadeOut, 4f, .7f, fx, fy, fadeCorner, small, outerCorner,
                Math.min(fadeCorner, small), Math.abs(outerCorner - radius) < .000001f ? 1f : 0f,
                Math.min(outerCorner, small), fx - fadeCorner, fy - fadeCorner, x - outerCorner, y - outerCorner};
    }

    private static float[] fade(float width, float height, float start, float length,
                                float refWidth, float refHeight) {
        float perimeter = 2f * (Math.max(width, 1f) + Math.max(height, 1f));
        float refPerimeter = 2f * (refWidth + refHeight);
        float refLength = refPerimeter * (start + length);
        float mappedStart;
        float mappedLength;
        if (refLength <= refWidth) {
            float total = (Math.max(width, 1f) + refLength - refWidth) / perimeter;
            mappedStart = start * total / (start + length);
            mappedLength = length * total / (start + length);
        } else {
            mappedLength = length * refPerimeter / perimeter;
            mappedStart = (Math.max(width, 1f) + refLength - refWidth - length * refPerimeter) / perimeter;
        }
        return new float[]{mappedStart, mappedStart + mappedLength};
    }

    private static final String SOURCE = """
                float lum(float3 color) {
                    return dot(color, float3(0.3, 0.59, 0.11));
                }

                float3 luminosity(float3 source, float3 destination) {
                    float destinationLum = lum(destination);
                    float sourceLum = lum(source);
                    float3 color = destination + (sourceLum - destinationLum);
                    float minColor = min(min(color.x, color.y), color.z);
                    float maxColor = max(max(color.x, color.y), color.z);
                    if (minColor < 0.0) {
                        return sourceLum + ((color - sourceLum) * sourceLum) / (sourceLum - minColor);
                    } else if (maxColor > 1.0) {
                        return sourceLum + ((color - sourceLum) * (1.0 - sourceLum)) / (maxColor - sourceLum);
                    }
                    return color;
                }

                float overlay(float source, float destination) {
                    return destination < 0.5
                        ? 2.0 * source * destination
                        : 1.0 - 2.0 * (1.0 - source) * (1.0 - destination);
                }

                float3 overlay(float3 source, float3 destination) {
                    return float3(
                        overlay(source.x, destination.x),
                        overlay(source.y, destination.y),
                        overlay(source.z, destination.z)
                    );
                }

                half4 couiMaterial(half4 inColor, float4 mixColor, float4 blendColor) {
                    float3 luminosityColor = luminosity(mixColor.rgb, inColor.rgb);
                    inColor.rgb = mix(inColor.rgb, luminosityColor, mixColor.a);
                    float3 overlayColor = overlay(blendColor.rgb, inColor.rgb);
                    inColor.rgb = mix(inColor.rgb, overlayColor, blendColor.a);
                    return inColor;
                }
            uniform float u_shadowArray[28];
            uniform float u_shadowShapeCtxArray[24];
            uniform float u_edgeArray[28];
            struct CommonDataRect{
            vec2 halfResolution;
            vec2 pos;
            vec2 halfSize;
            vec2 invScale;
            float coner;
            float weight;
            }
            ;
            struct ShapeResult {
            float sdf;
             float mixC;
            }
            ;
            struct RectCorner {
            float2 center;
            float2 halfSize;
            float coner;
            float weight;
            }
            ;
            const float EPS = 1e-6;
            const float PI = 3.1415926;
            const float ANTIALIASING = 2.0;
            const float BLEND_EDGE_FADE_RANGE_X = 0.0;
            const float BLEND_EDGE_FADE_RANGE_Y = 5.0;
            const float BLEND_SHAPE_CHANGE = 5.0;
            const float BLEND_EDGE_FADE = 50.0;
            const float BLEND_STRENGTH_SMOOTH = 26.0;
            const float BLEND_STRENGTH = 25.0;
            const float BLEND_COLOR_SPEED_PIXEL = 20.0;
            const float BLEND_COLOR_RANGE = 50.0;
            const float BLEND_COLOR_FADE = 0.883;
            const float BLEND_PROCESS_CONTROL = 0.3;
            const float BLEND_ARC_END = 0.85;
            const float BLEND_ARC_START = 1.0;
            const float BLEND_ARC_RANGE = 12.5;
            float RBox(float2 p,float2 b,float r){
            float2 q=abs(p)-b+r;
            return min(max(q.x,q.y),0.)+length(max(q,0.))-r;
            }
            struct ShapeCtx{
            float2 halfSize;
            float weight;
            float r;
            bool isVert;
            float2 hs;
            float a;
            float kMaxEff;
            float xBandEff;
            float yBandEff;
            float transitionPixels;
            }
            ;
            const float DEFAULT_TRANSITION_PIXELS = 4.0;
            const float kAbs = 1.0;
            const float rRef = 80.0;
            const float kMax = 26.6;
            const float junctionXBand = 70.0;
            const float junctionYBand = 60.0;
            const float junctionPower = 1.0;
            float smoothAbs(float x, float k) {
            return sqrt(x * x + k * k);
            }
            float smoothMaxSqrt(float a, float b, float k) {
            float d = a - b;
            return 0.5 * (a + b + sqrt(d * d + k * k));
            }
            ShapeCtx shapeCtxFromValues(float2 halfSize, float weight, float r, float isVert,float2 hs, float a, float kMaxEff,float xBandEff, float yBandEff, float transitionPixels) {
            ShapeCtx ctx;
            ctx.halfSize = halfSize;
            ctx.weight = weight;
            ctx.r = r;
            ctx.isVert = isVert > 0.5;
            ctx.hs = hs;
            ctx.a = a;
            ctx.kMaxEff = kMaxEff;
            ctx.xBandEff = xBandEff;
            ctx.yBandEff = yBandEff;
            ctx.transitionPixels = transitionPixels;
            return ctx;
            }
            ShapeCtx shapeCtxFromArray(float data[12]) {
            return shapeCtxFromValues(float2(data[0], data[1]), data[2], data[3], data[4],float2(data[5], data[6]), data[7], data[8],data[9], data[10], data[11]);
            }
            ShapeCtx shadowShapeCtxFromArray(float data[24], int offset) {
            // 调用只使用两组固定槽位; 常量下标同时支持不含 ES3 特性的表面 Shader。
            if (offset == 0) {
            return shapeCtxFromValues(float2(data[0], data[1]), data[2], data[3], data[4], float2(data[5], data[6]), data[7], data[8], data[9], data[10], data[11]);
            }
            return shapeCtxFromValues(float2(data[12], data[13]), data[14], data[15], data[16], float2(data[17], data[18]), data[19], data[20], data[21], data[22], data[23]);
            }
            float sdCapsule(float2 pLocal, ShapeCtx ctx, inout float zoneCap) {
            float2 pl = ctx.isVert ? pLocal.yx : pLocal;
            float2 q = float2(smoothAbs(pl.x, kAbs), smoothAbs(pl.y, kAbs));
            float r = ctx.hs.y;
            zoneCap = abs(pl.x) - ctx.a;
            float s = q.x - ctx.a;
            float xHard = max(s, 0.0);
            float xSoft = (ctx.kMaxEff > 0.0) ? smoothMaxSqrt(s, 0.0, ctx.kMaxEff) : xHard;
            float wx = 1.0 - smoothstep(0.0, max(ctx.xBandEff, 1e-3), abs(s));
            float wy = smoothstep(r - max(ctx.yBandEff, 1e-3), r, q.y);
            float w = clamp(wx * wy, 0.0, 1.0);
            float x = mix(xHard, xSoft, w);
            return length(float2(x, q.y)) - r;
            }
            float powFast(float x, float n) {
            x = max(x, 1e-8);
            return exp2(log2(x) * n);
            }
            float lpNorm(float2 v, float n) {
            v = abs(v);
            if (n < 2.0 + EPS) {
            return length(v);
            }
            float m = max(v.x, v.y);
            if (m < 1e-8) return 0.0;
            float2 u = v / m;
            float s = powFast(u.x, n) + powFast(u.y, n);
            return m * exp2(log2(max(s, EPS)) / n);
            }
            float sdRoundRect(float2 p, float2 halfSize, float r, float n, inout float2 q) {
            q = abs(p) - halfSize + float2(r);
            float dCorner = lpNorm(max(q, 0.0), n);
            if (q.x > 0.0 && q.y > 0.0) return dCorner - r;
            else return max(q.x, q.y) - r;
            }
            float smoothSdfD(float2 p, ShapeCtx ctx, inout float aaFactor){
            float rectSdf = 0.0;
            float cornerZone = 0.0;
            float2 qRect = float2(0.0, 0.0);
            float transitionPixels = DEFAULT_TRANSITION_PIXELS;
            if (ctx.weight  < 2.0) {
            transitionPixels = ctx.transitionPixels;
            float zoneCap = 0.0;
            if(ctx.weight  > 1.005){
            float t = smoothstep(2.0, 1.0, ctx.weight);
            float dG2 = sdRoundRect( p, ctx.halfSize, ctx.r, 2.0, qRect);
            float zoneG2 = min(qRect.x, qRect.y);
            float dCapsule = sdCapsule( p, ctx, zoneCap);
            rectSdf = mix(dG2, dCapsule, t);
            cornerZone = mix(zoneG2, zoneCap, t);
            }
             else {
            rectSdf = sdCapsule( p, ctx, zoneCap);
            cornerZone = zoneCap;
            }
            }
             else {
            rectSdf = sdRoundRect( p, ctx.halfSize, ctx.r, ctx.weight, qRect);
            cornerZone = min(qRect.x, qRect.y);
            }
            aaFactor = smoothstep(-transitionPixels - 2.0, -transitionPixels * 0.2, cornerZone);
            return rectSdf;
            }
                    float colorDodge(float s, float d) {
                        return d / (1.0 - s + 0.00000001);
                    }

                    float3 colorDodge(float3 s, float3 d) {
                        return float3(colorDodge(s.x, d.x), colorDodge(s.y, d.y), colorDodge(s.z, d.z));
                    }

                    // Shade Light 的面板混色在前, 普通有描边卡片混色在后; 参数来自 PJZ110 SystemUI。
                    half4 lightEdgeReferenceColor(half4 color) {
                        color = couiMaterial(color,
                            float4(0.0, 0.0, 0.0, 146.0 / 255.0),
                            float4(float3(153.0 / 255.0), 102.0 / 255.0));
                        color.rgb = mix(color.rgb, luminosity(float3(64.0 / 255.0), color.rgb), 25.0 / 255.0);
                        color.rgb = mix(color.rgb, colorDodge(float3(115.0 / 255.0), color.rgb), 77.0 / 255.0);
                        return color;
                    }

                    half4 couiEdgeColor(half4 rawColor, float3 lineColor, bool limitHighlights) {
                        float gray = clamp(dot(float3(0.2126, 0.7152, 0.0722), rawColor.rgb)*1.2,0.0,1.0);
                        float edgeImprove = (1.0 - gray);
                        edgeImprove *= edgeImprove;
                        edgeImprove *= edgeImprove;
                        edgeImprove *= 0.1;
                        half4 color = clamp(rawColor + half4(float3(edgeImprove), 0.0), 0.0, 1.0);
                        color.rgb = mix(2.0 * color.rgb * lineColor, 1.0 - 2.0 * (1.0 - color.rgb) * (1.0 - lineColor), step(lineColor, float3(0.5)));
                        // 等比例收回过亮描边，避免逐通道截断后变成白边。
                        if (limitHighlights) {
                            float peak = max(max(color.r, color.g), color.b);
                            color.rgb /= max(1.0, peak);
                        }
                        return color;
                    }
            float random(float2 st){
            return fract(sin(dot(st.yx,float2(79.1214,78.233)))*43760.5453);
            }
            float softLight(float A,float B){
            float l=B-(1.-2.*A)*B*(1.-B);
            float h=B+(2.*A-1.)*(sqrt(B)-B);
            return mix(l,h,step(.5,A));
            }
            float getShadowAlpha(float fadeLine, float fadeSdf, float d, float para1, float para2){
            float alpha = smoothstep(fadeLine,0.0,fadeSdf)*smoothstep(fadeLine, 0.0, d);
            if(para2>EPS){
            alpha = pow(alpha, para1)*para2+(1.0-para2)*alpha;
            }
            return alpha;
            }
            half4 blendShadowColor(float2 coords, CommonDataRect rectData, half4 color, float d, bool useMenuLightEdge){
            float3 uFadeAlpha = float3(u_shadowArray[3], u_shadowArray[4], u_shadowArray[5]);
            float fadeAlpha = 0.0;
            vec3 fadeAlphaVec3 = uFadeAlpha;
            float gray = clamp((dot(vec3(0.2126, 0.7152, 0.0722), color.rgb) - 0.6) * 1.6, 0.0, 1.0);
            fadeAlphaVec3 =  fadeAlphaVec3*(1.0 - gray)*(1.0-gray);
            if(all(lessThanEqual(fadeAlphaVec3, vec3(EPS)))){
            return color;
            }
            if(any(greaterThan(fadeAlphaVec3.xy, float2(EPS)))){
            float uFadeIn = u_shadowArray[6];
            float2 uFadeOffset = float2(u_shadowArray[7], u_shadowArray[8]);
            float uFadeInPara1 =  u_shadowArray[11];
            float uFadeInPara2 =  u_shadowArray[12];
            float2 posNew = (rectData.pos-uFadeOffset);
            float fadeLine = uFadeIn*-1.0;
            float fadeSdf = fadeLine;
            float2 halfSizeFade = float2(u_shadowArray[16], u_shadowArray[17]);
            float fadeConerX = u_shadowArray[18];
            if(rectData.weight<2.0 || any(greaterThan(abs(posNew), float2(u_shadowArray[24], u_shadowArray[25])))){
            float aaFactor = 0.0;
            fadeSdf = smoothSdfD(posNew, shadowShapeCtxFromArray(u_shadowShapeCtxArray, 0), aaFactor);
            float alpha = getShadowAlpha(fadeLine, fadeSdf, d, uFadeInPara1, uFadeInPara2);
            alpha = alpha*(fadeAlphaVec3.x-fadeAlphaVec3.y) + fadeAlphaVec3.y ;
              fadeAlpha = alpha;
            }
            else{
            fadeAlpha = fadeAlphaVec3.y;
            }
            }
            if(fadeAlphaVec3.z>EPS){
            float uFadeOut = u_shadowArray[13];
            float uFadeOutPara1 =  u_shadowArray[14];
            float uFadeOutPara2 = u_shadowArray[15];
            float fadeConerY = u_shadowArray[20];
            float fadeLine =  uFadeOut *-1.0;
            float fadeSdf = fadeLine;
            if(rectData.weight<2.0 || any(greaterThan(abs(rectData.pos), float2(u_shadowArray[26], u_shadowArray[27])))){
            if(u_shadowArray[22] > 0.5){
            fadeSdf = d;
            }
            else{
            float aaFactor = 0.0;
            fadeSdf = smoothSdfD(rectData.pos, shadowShapeCtxFromArray(u_shadowShapeCtxArray, 12), aaFactor);
            }
            float alpha = getShadowAlpha(fadeLine, fadeSdf, d, uFadeOutPara1, uFadeOutPara2);
            alpha *= fadeAlphaVec3.z ;
            fadeAlpha = mix(fadeAlpha, 1.0,alpha);
            }
            }
            if(fadeAlpha > EPS){
            float2 uvPos=coords.xy*0.000925;
            float n=clamp((random(uvPos)-.5)*0.008+.5,0.,1.);
            fadeAlpha=clamp(softLight(n,fadeAlpha),0.0,1.0);
            }
            float3 uFadeColor = float3(u_shadowArray[0], u_shadowArray[1], u_shadowArray[2]);
            half4 fadeColor = half4(uFadeColor, fadeAlpha);
            // 菜单式内晕按覆盖率混色，避免直接叠亮后截断高光。
            if (!useMenuLightEdge) fadeColor.rgb += color.rgb;
            return half4(clamp(mix(color.rgb, fadeColor.rgb, fadeColor.a), 0.0,1.0),1.0);
            }
            float getDis(float2 pos,float2 halfsize){
            float2 rect=abs(pos-halfsize);
            return rect.x+rect.y;
            }
            half4 blendEdge(CommonDataRect rectData, half4 color, half4 rawColor, float d, bool useMenuLightEdge){
            float2 uRectLineAlpha = float2(u_edgeArray[7], u_edgeArray[8]);
            if(all(lessThanEqual(uRectLineAlpha, float2(EPS)))){
            return color;
            }
            float2 uRectLineNear = float2(u_edgeArray[3], u_edgeArray[4]);
            float2 uRectLineFar = float2(u_edgeArray[5], u_edgeArray[6]);
            if(d<-u_edgeArray[27]){
            return color;
            }
            float3 uRectLineColor = float3(u_edgeArray[0], u_edgeArray[1], u_edgeArray[2]);
            float uRectLinePara1 = u_edgeArray[14];
            float uRectLinePara2 = u_edgeArray[15];
            float distanceAll = u_edgeArray[16];
            float2 halfSize = rectData.halfSize;
            float2 dirPos= normalize(rectData.pos);
            float2 dirAbsPos = abs(dirPos);
            float2 posN = min(float2(dirAbsPos.xy*halfSize.yx/max(float2(.001),dirAbsPos.yx)),halfSize)*sign(dirPos);
            float2 halfSizeSymbol = float2(u_edgeArray[24], u_edgeArray[25]);
            float flag = u_edgeArray[19]*dot(halfSizeSymbol,posN);
            float disPoint = u_edgeArray[18];
            float disPos = getDis(posN,halfSize);
            float distanceNear = mix(disPoint+disPos,abs(disPoint-disPos),step(0.,flag));
            distanceNear=min(u_edgeArray[26]-distanceNear,distanceNear);
            float lineNearAlpha = uRectLineAlpha.x*smoothstep(u_edgeArray[20], u_edgeArray[21], distanceNear);
            float lineFarAlpha = uRectLineAlpha.y*smoothstep(u_edgeArray[22], u_edgeArray[23], distanceAll-distanceNear);
            float lineNearSdf=smoothstep(-uRectLineNear.y,-uRectLineNear.x,d);
            float lineFarSdf=smoothstep(-uRectLineFar.y,-uRectLineFar.x,d);
            if(uRectLinePara2 > EPS){
            lineNearSdf = pow(lineNearSdf, uRectLinePara1)*uRectLinePara2+(1.0-uRectLinePara2)*lineNearSdf;
            lineFarSdf = pow(lineFarSdf, uRectLinePara1)*uRectLinePara2+(1.0-uRectLinePara2)*lineFarSdf;
            }
            lineNearAlpha*=lineNearSdf;
            lineFarAlpha*=lineFarSdf;
            float lineAlpha = clamp(lineNearAlpha+lineFarAlpha,0.,1.);
            if (useMenuLightEdge) rawColor = lightEdgeReferenceColor(rawColor);
            return mix(color, couiEdgeColor(rawColor, uRectLineColor, useMenuLightEdge), lineAlpha);
            }

            uniform float u_commonArray[8];
            uniform float u_baseShapeCtxArray[12];
            uniform float4 uSurfaceColor;
            uniform float uEffectAlpha;
            half4 main(float2 fragCoord) {
                CommonDataRect rectData;
                float2 coords = float2(fragCoord.x, u_commonArray[1] * 2.0 - fragCoord.y);
                rectData.halfResolution = float2(u_commonArray[0], u_commonArray[1]);
                rectData.pos = coords - rectData.halfResolution;
                rectData.halfSize = float2(u_commonArray[4], u_commonArray[5]);
                rectData.invScale = float2(1.0);
                rectData.coner = u_commonArray[6];
                rectData.weight = u_commonArray[7];
                float aaFactor = 0.0;
                float d = smoothSdfD(rectData.pos, shapeCtxFromArray(u_baseShapeCtxArray), aaFactor);
                half4 surface = half4(uSurfaceColor.rgb, 1.0);
                half4 color = blendShadowColor(coords, rectData, surface, d, false);
                if (dot(rectData.pos, rectData.pos) >= EPS) {
                    color = blendEdge(rectData, color, surface, d, false);
                }
                color.rgb = mix(surface.rgb, clamp(color.rgb, 0.0, 1.0), uEffectAlpha);
                return half4(color.rgb * uSurfaceColor.a, uSurfaceColor.a);
            }
            """;
}
