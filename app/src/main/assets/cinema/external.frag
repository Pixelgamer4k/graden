#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 uv;out vec4 frag;
uniform samplerExternalOES uImage;
uniform mat4 uTransform;
uniform int uRotation;
void main(){
 vec2 p=uv;
 if(uRotation==90)p=vec2(uv.y,1.0-uv.x);
 else if(uRotation==180)p=1.0-uv;
 else if(uRotation==270)p=vec2(1.0-uv.y,uv.x);
 p.y=1.0-p.y;
 frag=texture(uImage,(uTransform*vec4(p,0.0,1.0)).xy);
}
