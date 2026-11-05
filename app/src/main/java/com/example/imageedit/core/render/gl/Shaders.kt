package com.example.imageedit.core.render.gl

object Shaders {
    /** Fullscreen triangle strip with top-left origin UVs for Bitmap textures. */
    const val VERTEX = """#version 300 es
in vec2 aPosition;
out vec2 vUv;
void main() {
    vUv = vec2(aPosition.x * 0.5 + 0.5, 1.0 - (aPosition.y * 0.5 + 0.5));
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

    const val DISPLAY = """#version 300 es
precision highp float;
uniform sampler2D uTexture;
in vec2 vUv;
out vec4 fragColor;
void main() {
    fragColor = texture(uTexture, vUv);
}
"""

    /**
     * Bitmap pixels are premultiplied; unpremultiply before applying the matrix
     * and re-premultiply afterwards to match ColorMatrixColorFilter.
     */
    const val COLOR_ADJUST = """#version 300 es
precision highp float;
uniform sampler2D uTexture;
uniform mat4 uColorMatrix;
uniform vec4 uColorOffset;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec4 source = texture(uTexture, vUv);
    float alpha = source.a;
    vec3 rgb = alpha > 0.0001 ? source.rgb / alpha : source.rgb;
    vec4 adjusted = uColorMatrix * vec4(rgb, 1.0) + uColorOffset;
    fragColor = vec4(clamp(adjusted.rgb, 0.0, 1.0) * alpha, alpha);
}
"""

    const val MASK_BLEND = """#version 300 es
precision highp float;
uniform sampler2D uAdjusted;
uniform sampler2D uOriginal;
uniform sampler2D uMask;
in vec2 vUv;
out vec4 fragColor;
void main() {
    float mask = texture(uMask, vUv).r;
    // uAdjusted is an FBO texture; its origin is bottom-left relative to the
    // bitmap-top-left uploads used by uOriginal/uMask.
    vec4 adjusted = texture(uAdjusted, vec2(vUv.x, 1.0 - vUv.y));
    fragColor = mix(texture(uOriginal, vUv), adjusted, mask);
}
"""

    const val GARMENT_HUE = """#version 300 es
precision highp float;
uniform sampler2D uTexture;
uniform sampler2D uMask;
uniform float uHueShift;
uniform float uSatScale;
in vec2 vUv;
out vec4 fragColor;

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

void main() {
    vec4 source = texture(uTexture, vUv);
    float alpha = source.a;
    vec3 rgb = alpha > 0.0001 ? source.rgb / alpha : source.rgb;
    vec3 hsv = rgb2hsv(rgb);
    hsv.x = fract(hsv.x + uHueShift / 360.0);
    hsv.y = clamp(hsv.y * uSatScale, 0.0, 1.0);
    vec3 recolored = hsv2rgb(hsv);

    // Match GarmentOps.forceValuePreserved: V must equal the source V.
    float sourceV = max(rgb.r, max(rgb.g, rgb.b));
    float recoloredV = max(recolored.r, max(recolored.g, recolored.b));
    if (recoloredV > 0.0001) {
        recolored *= sourceV / recoloredV;
    }
    if (recolored.r >= recolored.g && recolored.r >= recolored.b) {
        recolored.r = sourceV;
    } else if (recolored.g >= recolored.r && recolored.g >= recolored.b) {
        recolored.g = sourceV;
    } else {
        recolored.b = sourceV;
    }

    float mask = texture(uMask, vUv).r;
    fragColor = vec4(mix(rgb, recolored, mask) * alpha, alpha);
}
"""

    const val TEXTURE_WARP = """#version 300 es
precision highp float;
uniform sampler2D uTexture;
uniform sampler2D uMask;
uniform sampler2D uPattern;
uniform vec2 uTexel;
uniform float uStrength;
in vec2 vUv;
out vec4 fragColor;

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec4 source = texture(uTexture, vUv);
    float alpha = source.a;
    vec3 rgb = alpha > 0.0001 ? source.rgb / alpha : source.rgb;

    vec2 t = uTexel;
    float g00 = luma(texture(uTexture, vUv + t * vec2(-1.0, -1.0)).rgb);
    float g01 = luma(texture(uTexture, vUv + t * vec2( 0.0, -1.0)).rgb);
    float g02 = luma(texture(uTexture, vUv + t * vec2( 1.0, -1.0)).rgb);
    float g10 = luma(texture(uTexture, vUv + t * vec2(-1.0,  0.0)).rgb);
    float g12 = luma(texture(uTexture, vUv + t * vec2( 1.0,  0.0)).rgb);
    float g20 = luma(texture(uTexture, vUv + t * vec2(-1.0,  1.0)).rgb);
    float g21 = luma(texture(uTexture, vUv + t * vec2( 0.0,  1.0)).rgb);
    float g22 = luma(texture(uTexture, vUv + t * vec2( 1.0,  1.0)).rgb);
    float sobelX = (g02 + 2.0 * g12 + g22) - (g00 + 2.0 * g10 + g20);
    float sobelY = (g20 + 2.0 * g21 + g22) - (g00 + 2.0 * g01 + g02);
    vec2 offsetTexels = vec2(sobelX, sobelY) * (uStrength / 10.0);
    vec3 pattern = texture(uPattern, vUv + offsetTexels * uTexel).rgb;

    vec3 hsv = rgb2hsv(rgb);
    hsv.z = max(pattern.r, max(pattern.g, pattern.b));
    vec3 textured = hsv2rgb(hsv);
    float mask = texture(uMask, vUv).r;
    fragColor = vec4(mix(rgb, textured, mask) * alpha, alpha);
}
"""

    const val BACKGROUND = """#version 300 es
precision highp float;
uniform sampler2D uForeground;
uniform sampler2D uBackground;
uniform vec2 uBgScale;
uniform vec2 uBgOffset;
uniform float uUseSolid;
uniform vec4 uSolidColor;
uniform float uContactShadow;
uniform vec2 uTexel;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec2 bgUv = vUv * uBgScale + uBgOffset;
    vec3 background;
    if (uUseSolid > 0.5) {
        background = uSolidColor.rgb;
    } else if (any(lessThan(bgUv, vec2(0.0))) || any(greaterThan(bgUv, vec2(1.0)))) {
        background = vec3(0.0);
    } else {
        background = texture(uBackground, bgUv).rgb;
    }

    if (uContactShadow > 0.5) {
        vec2 shadowUv = vUv - vec2(0.008, 0.014);
        float shadow = 0.0;
        shadow += texture(uForeground, shadowUv + uTexel * vec2(-2.0, -2.0)).a;
        shadow += texture(uForeground, shadowUv + uTexel * vec2( 0.0, -2.0)).a;
        shadow += texture(uForeground, shadowUv + uTexel * vec2( 2.0, -2.0)).a;
        shadow += texture(uForeground, shadowUv + uTexel * vec2(-2.0,  0.0)).a;
        shadow += texture(uForeground, shadowUv).a;
        shadow += texture(uForeground, shadowUv + uTexel * vec2( 2.0,  0.0)).a;
        shadow += texture(uForeground, shadowUv + uTexel * vec2(-2.0,  2.0)).a;
        shadow += texture(uForeground, shadowUv + uTexel * vec2( 0.0,  2.0)).a;
        shadow += texture(uForeground, shadowUv + uTexel * vec2( 2.0,  2.0)).a;
        shadow /= 9.0;
        background = mix(background, vec3(0.0), clamp(shadow * 0.35, 0.0, 1.0));
    }

    vec4 foreground = texture(uForeground, vUv);
    fragColor = vec4(foreground.rgb + background * (1.0 - foreground.a), 1.0);
}
"""

    const val BLUR = """#version 300 es
precision highp float;
uniform sampler2D uTexture;
uniform vec2 uStep;
in vec2 vUv;
out vec4 fragColor;
void main() {
    float w0 = 0.227027;
    float w1 = 0.1945946;
    float w2 = 0.1216216;
    float w3 = 0.054054;
    float w4 = 0.016216;
    vec4 sum = texture(uTexture, vUv) * w0;
    sum += texture(uTexture, vUv + uStep) * w1;
    sum += texture(uTexture, vUv - uStep) * w1;
    sum += texture(uTexture, vUv + uStep * 2.0) * w2;
    sum += texture(uTexture, vUv - uStep * 2.0) * w2;
    sum += texture(uTexture, vUv + uStep * 3.0) * w3;
    sum += texture(uTexture, vUv - uStep * 3.0) * w3;
    sum += texture(uTexture, vUv + uStep * 4.0) * w4;
    sum += texture(uTexture, vUv - uStep * 4.0) * w4;
    fragColor = sum;
}
"""

    const val TRANSFORM = """#version 300 es
precision highp float;
uniform sampler2D uTexture;
uniform vec3 uUvX;
uniform vec3 uUvY;
in vec2 vUv;
out vec4 fragColor;
void main() {
    vec2 uv = vec2(
        dot(uUvX, vec3(vUv, 1.0)),
        dot(uUvY, vec3(vUv, 1.0))
    );
    fragColor = texture(uTexture, uv);
}
"""

    const val CHECKER = """#version 300 es
precision highp float;
out vec4 fragColor;
void main() {
    vec2 cell = floor(gl_FragCoord.xy / 16.0);
    float odd = mod(cell.x + cell.y, 2.0);
    vec3 color = mix(vec3(0.15), vec3(0.23), odd);
    fragColor = vec4(color, 1.0);
}
"""
}
