#version 120

// The 1.21.11 velocity_pre/velocity_post shaders (natural-motionblur), in
// GLSL 1.20 for 1.8.9's legacy GL context. Post is 1 for the pass after the
// world (depth blur with camera blur cancelled), 0 for the pass before
// entities (camera blur with depth blur cancelled).

uniform sampler2D MainSampler;
uniform sampler2D MainDepthSampler;
uniform mat4 mvInverse;
uniform mat4 projInverse;
uniform mat4 prevModelView;
uniform mat4 prevProjection;
uniform vec3 cameraDelta;
uniform vec2 view_res;
uniform float blendFactor;
uniform int sampleCount;
uniform int post;

varying vec2 texCoord;

vec3 reproject(vec3 screenPos) {
    vec3 ndc      = screenPos * 2.0 - 1.0;
    vec4 viewPos  = projInverse * vec4(ndc, 1.0);
    vec3 worldPos = (mvInverse * vec4(viewPos.xyz / viewPos.w, 1.0)).xyz + cameraDelta;
    vec4 prevClip = prevProjection * (prevModelView * vec4(worldPos, 1.0));
    return (prevClip.xyz / prevClip.w) * 0.5 + 0.5;
}

vec2 clampLength(vec2 velocity) {
    float lenSq = dot(velocity, velocity);
    return (lenSq > 0.16) ? velocity * (0.4 * inversesqrt(lenSq)) : velocity;
}

float noise(vec2 pos) {
    return fract(52.9829189 * fract(0.06711056 * pos.x + 0.00583715 * pos.y));
}

void main() {
    vec2 oneTexel = 1.0 / view_res;
    float depth = texture2D(MainDepthSampler, texCoord).x;

    // hand fix
    if (depth < 0.56) { gl_FragColor = texture2D(MainSampler, texCoord); return; }
    // depth blend inconsistency fix
    float dilatedDepth = depth;
    dilatedDepth = min(dilatedDepth, texture2D(MainDepthSampler, texCoord + vec2( oneTexel.x, 0.0)).x);
    dilatedDepth = min(dilatedDepth, texture2D(MainDepthSampler, texCoord + vec2(-oneTexel.x, 0.0)).x);
    dilatedDepth = min(dilatedDepth, texture2D(MainDepthSampler, texCoord + vec2(0.0,  oneTexel.y)).x);
    dilatedDepth = min(dilatedDepth, texture2D(MainDepthSampler, texCoord + vec2(0.0, -oneTexel.y)).x);

    vec2 velFull   = texCoord - reproject(vec3(texCoord, dilatedDepth)).xy;
    vec2 velCamera = texCoord - reproject(vec3(texCoord, 1.0)).xy;
    float camMag   = dot(velCamera, velCamera);
    vec2 velocity;
    if (post == 1) {
        velocity = clampLength(camMag > 1e-12 ? velCamera * (clamp(dot(velFull, velCamera), 0.0, camMag) / camMag) : vec2(0.0));
    } else {
        velocity = clampLength(camMag > 1e-12 ? velFull - velCamera * (clamp(dot(velFull, velCamera), 0.0, camMag) / camMag) : velFull);
    }

    float speed   = length(velocity);
    int   samples = int(clamp(ceil(speed * float(sampleCount)), 4.0, float(sampleCount)));

    vec2  step         = (blendFactor * velocity) / float(samples);
    float centerOffset = -float(samples) * 0.5;
    vec2  seed         = texCoord * view_res;
    vec3  sum          = vec3(0.0);

    for (int i = 0; i < 32; i++) {
        if (i >= samples) break;
        float fi     = float(i);
        float jitter = noise(seed + vec2(fi, fi * 1.4));
        vec2  pos    = texCoord + (fi + centerOffset + jitter) * step;
        vec3  c      = texture2D(MainSampler, pos).rgb;
        sum         += c * c;
    }
    gl_FragColor = vec4(sqrt(sum / float(samples)), 1.0);
}
