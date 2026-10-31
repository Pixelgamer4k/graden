#version 300 es
precision highp float;
in vec2 uv;out vec4 frag;
uniform sampler2D uImage;
uniform int uFlip;
void main(){frag=texture(uImage,vec2(uv.x,uFlip==1?1.0-uv.y:uv.y));}
