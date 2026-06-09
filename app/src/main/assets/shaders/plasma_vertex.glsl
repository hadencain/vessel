#version 300 es
precision highp float;

layout(location = 0) in vec2 a_position;  // NDC [-1, 1]
layout(location = 1) in vec2 a_texCoord;  // UV [0, 1]

out vec2 v_texCoord;

void main() {
    v_texCoord = a_texCoord;
    gl_Position = vec4(a_position, 0.0, 1.0);
}
