#version 150

uniform sampler2D DiffuseSampler;
uniform float Intensity;

in vec2 texCoord;
in vec2 oneTexel;

out vec4 fragColor;

void main() {
    vec4 color = texture(DiffuseSampler, texCoord);

    float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    vec3 gray = vec3(luma);

    vec3 result = mix(color.rgb, gray, clamp(Intensity, 0.0, 1.0));
    fragColor = vec4(result, color.a);
}
