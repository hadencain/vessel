#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;

// ── Uniforms ──────────────────────────────────────────────────────────────────
uniform samplerExternalOES u_cameraTexture;
uniform float              u_time;
uniform vec2               u_resolution;
uniform vec3               u_artifactWorldPos;
uniform mat4               u_viewProjection;
uniform float              u_contactMagnitude;
uniform vec3               u_contactPoint;
uniform float              u_breathPhase;    // unused — kept for pipeline compat
uniform float              u_bleedHue;
uniform float              u_bleedEnabled;
uniform mat3               u_uvTransform;
uniform vec3               u_smearDir;
uniform float              u_smearSpeed;

in  vec2 v_texCoord;
out vec4 fragColor;

// ── Noise ─────────────────────────────────────────────────────────────────────
float hash(vec3 p) {
    p = fract(p * vec3(127.1, 311.7, 74.7));
    p += dot(p, p.yxz + 19.19);
    return fract((p.x + p.y) * p.z);
}

float noise(vec3 p) {
    vec3 i = floor(p); vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash(i),             hash(i+vec3(1,0,0)), f.x),
            mix(hash(i+vec3(0,1,0)), hash(i+vec3(1,1,0)), f.x), f.y),
        mix(mix(hash(i+vec3(0,0,1)), hash(i+vec3(1,0,1)), f.x),
            mix(hash(i+vec3(0,1,1)), hash(i+vec3(1,1,1)), f.x), f.y), f.z);
}

// ── Camera ────────────────────────────────────────────────────────────────────
vec4 sampleCamera(vec2 uv, vec2 offset) {
    vec2 r = (u_uvTransform * vec3(uv + offset * 1.0, 1.0)).xy;
    vec2 g = (u_uvTransform * vec3(uv + offset * 0.7, 1.0)).xy;
    vec2 b = (u_uvTransform * vec3(uv + offset * 0.4, 1.0)).xy;
    return vec4(texture(u_cameraTexture, r).r,
                texture(u_cameraTexture, g).g,
                texture(u_cameraTexture, b).b, 1.0);
}

vec3 hsvToRgb(float h, float s, float v) {
    vec3 c = clamp(abs(mod(h*6.0 + vec3(0,4,2), 6.0) - 3.0) - 1.0, 0.0, 1.0);
    return v * mix(vec3(1.0), c, s);
}

// ── Rankine vortex velocity (analytical, cheap) ───────────────────────────────
vec2 vortexVel(vec2 p, vec2 center, float gamma, float rCore) {
    vec2  r    = p - center;
    float rLen = length(r) + 0.0001;
    float vT   = (rLen < rCore) ? gamma * rLen / (rCore * rCore) : gamma / rLen;
    return vec2(-r.y, r.x) / rLen * vT;
}

// ── Fast curl noise (1-octave, for advection loop) ────────────────────────────
// Single noise sample + finite difference — cheap enough to call 6× per pixel.
vec2 baseCurl(vec2 p, float t) {
    const float e = 0.007;
    float n0 = noise(vec3(p * 1.5,              t * 0.13));
    float nx = noise(vec3((p + vec2(e,0)) * 1.5, t * 0.13));
    float ny = noise(vec3((p + vec2(0,e)) * 1.5, t * 0.13));
    return vec2((ny - n0), -(nx - n0)) / e;
}

// ── Total flow field at point q ───────────────────────────────────────────────
vec2 flowAt(vec2 q, float t,
            bool hasContact, vec2 cp, float gamma,
            bool hasSmear, vec2 eddy0, vec2 eddy1, float smearGamma,
            vec2 sd2, float smear) {

    // Base organic curl + persistent center vortex (natural circulation at rest)
    vec2 F = baseCurl(q, t) * 0.5
           + vortexVel(q, vec2(0.0), 1.0, 0.20);

    if (hasContact) {
        F += vortexVel(q, cp, gamma, 0.08);
        if (hasSmear) {
            F += vortexVel(q, eddy0,  smearGamma, 0.06);
            F += vortexVel(q, eddy1, -smearGamma, 0.06);
            F += sd2 * smear * 0.4;
        }
    }
    return F;
}

// ── Main ─────────────────────────────────────────────────────────────────────
void main() {
    vec2 uv = v_texCoord;

    // Project artifact to screen UV
    vec4 clip = u_viewProjection * vec4(u_artifactWorldPos, 1.0);
    if (clip.w <= 0.0) { fragColor = sampleCamera(uv, vec2(0.0)); return; }
    vec2 artNDC = clip.xy / clip.w;
    vec2 artUV  = artNDC * 0.5 + 0.5;

    float aspect   = u_resolution.x / u_resolution.y;
    vec2  delta    = (uv - artUV) * vec2(aspect, 1.0);
    float distScrn = length(delta);

    const float screenRadius = 0.55;
    if (distScrn > screenRadius * 1.45) {
        fragColor = sampleCamera(uv, vec2(0.0));
        return;
    }

    float t  = u_time;
    float cm = u_contactMagnitude;
    vec2  p  = delta;  // pixel in artifact-relative half-NDC space

    // ── Contact & smear geometry ──────────────────────────────────────────────
    bool hasContact = cm > 0.01;
    vec2 cp = vec2(0.0);
    if (hasContact) {
        vec4 cpClip = u_viewProjection * vec4(u_contactPoint, 1.0);
        if (cpClip.w > 0.0)
            cp = (cpClip.xy / cpClip.w - artNDC) * vec2(aspect, 1.0) * 0.5;
    }

    float smear      = u_smearSpeed;
    vec2  sd2        = normalize(u_smearDir.xy + vec2(0.0001, 0.0));
    vec2  perp       = vec2(-sd2.y, sd2.x);
    float gamma      = cm * 2.5;
    float smearGamma = smear * cm * 1.1;
    bool  hasSmear   = hasContact && smear > 0.05;
    vec2  eddy0      = cp - sd2 * 0.13 + perp *  0.07;
    vec2  eddy1      = cp - sd2 * 0.13 - perp *  0.07;

    // ── Backward advection ────────────────────────────────────────────────────
    // Trace each pixel backward along the flow field to find its Lagrangian origin.
    // When a vortex (finger) is present, the path curves around it — particles
    // appear to part around the hand and continue on the other side.
    vec2 q = p;
    const int STEPS = 8;
    const float STEP_SIZE = 0.038;
    for (int i = 0; i < STEPS; i++) {
        vec2 F = flowAt(q, t - float(i) * 0.06,
                        hasContact, cp, gamma,
                        hasSmear, eddy0, eddy1, smearGamma,
                        sd2, smear);
        q -= normalize(F + vec2(0.0001)) * STEP_SIZE;
    }

    // ── Particle density at origin ────────────────────────────────────────────
    // Ridged noise at advected position q creates thin bright filaments on dark bg.
    // Two frequency layers — coarse structure + fine detail.
    float n1  = 1.0 - abs(noise(vec3(q * 11.0, t * 0.07)) * 2.0 - 1.0);
    float n2  = 1.0 - abs(noise(vec3(q * 22.0 + vec2(3.7, 1.9), t * 0.11)) * 2.0 - 1.0);
    float ridge = n1 * 0.65 + n2 * 0.35;

    // Sharp threshold → thin luminous filaments; wider threshold → broader glow regions
    float filament = smoothstep(0.60, 0.85, ridge);

    // ── Flow speed at pixel (for brightness + color) ──────────────────────────
    vec2 Fp = flowAt(p, t, hasContact, cp, gamma, hasSmear, eddy0, eddy1, smearGamma, sd2, smear);
    float flowSpeed = length(Fp);

    // ── Vortex proximity glow ─────────────────────────────────────────────────
    float vortexProx = 0.0;
    if (hasContact) {
        vortexProx = exp(-length(p - cp) * 13.0) * cm;
        if (hasSmear) {
            float dE = min(length(p - eddy0), length(p - eddy1));
            vortexProx += exp(-dE * 18.0) * smear * cm * 0.55;
        }
        vortexProx = clamp(vortexProx, 0.0, 1.0);
    }

    // ── Color ─────────────────────────────────────────────────────────────────
    vec3 restCol = vec3(0.00, 0.82, 0.72);
    vec3 fastCol = vec3(0.72, 0.96, 1.00);
    if (u_bleedEnabled > 0.5) {
        restCol = hsvToRgb(u_bleedHue, 0.85, 0.85);
        fastCol = mix(fastCol, hsvToRgb(u_bleedHue + 0.08, 0.4, 1.0), 0.5);
    }
    vec3 col = mix(restCol, fastCol, clamp(flowSpeed * 1.8, 0.0, 1.0));
    col = mix(col, vec3(1.0), vortexProx);

    // ── Distance fade ─────────────────────────────────────────────────────────
    float fade = 1.0 - clamp(distScrn / screenRadius, 0.0, 1.0);
    fade *= fade;

    // ── Composite ─────────────────────────────────────────────────────────────
    vec4 cam = sampleCamera(uv, vec2(0.0));

    vec3 particles  = col * filament * fade * (0.8 + flowSpeed * 1.1);
    vec3 vortexGlow = vec3(0.5, 0.88, 1.0) * vortexProx * fade * 0.75;
    vec3 ambientTint = restCol * clamp(flowSpeed * 0.2, 0.0, 1.0) * fade * 0.04;

    fragColor = vec4(cam.rgb + particles + vortexGlow + ambientTint, 1.0);
}
