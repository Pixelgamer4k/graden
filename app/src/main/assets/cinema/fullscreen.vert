#version 300 es
in vec2 aPosition;
out vec2 uv;
void main(){gl_Position=vec4(aPosition,0.0,1.0);uv=vec2(aPosition.x*0.5+0.5,0.5-aPosition.y*0.5);}
