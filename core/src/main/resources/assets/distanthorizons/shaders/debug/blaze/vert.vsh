#version 330 core

in vec3 vPosition;

layout (std140) uniform uniformBlock
{
    mat4 uTransform;
    vec4 uColor;
};

void main()
{
    gl_Position = uTransform * vec4(vPosition, 1.0);
}