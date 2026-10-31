#version 300 es
precision highp float;
in vec2 uv;out vec4 frag;
uniform sampler2D uImage,uDepth,uForeground;
uniform vec2 uSize,uMapSize;
uniform float uFocus,uStrength,uBand,uRotation,uHighlight,uRim,uBreathing;
uniform int uPortrait,uShowDepth,uOriginal,uShape,uSamples;
const float PI=3.14159265359;
vec3 source(vec2 p){return texture(uImage,vec2(p.x,1.-p.y)).rgb;}
vec3 linearize(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
vec3 encode(vec3 c){return mix(c*12.92,1.055*pow(max(c,vec3(0)),vec3(1./2.4))-.055,step(vec3(.0031308),c));}
vec2 rotate(vec2 p,float a){return mat2(cos(a),sin(a),-sin(a),cos(a))*p;}
float coc(float d){return uStrength*.022*min(uSize.x,uSize.y)*smoothstep(uBand,uBand+.5,abs(d-uFocus));}
vec2 aperture(int i,int count){float fi=float(i)+.5;vec2 q;if(uShape==1||uShape==2){float n=uShape==1?6.:8.;float blade=mod(float(i),n),r=sqrt((floor(float(i)/n)+.5)/ceil(float(count)/n));float a=2.*PI*blade/n,b=2.*PI*(blade+1.)/n;q=mix(vec2(cos(a),sin(a)),vec2(cos(b),sin(b)),fract(fi*.61803398875))*r;}else{float a=fi*2.39996322973;q=sqrt(fi/float(count))*vec2(cos(a),sin(a));if(uShape==3)q*=vec2(.7071068,1.4142136);}return rotate(q,uRotation);}
float gauge(vec2 p){p=rotate(p,-uRotation);if(uShape==3)p/=vec2(.7071068,1.4142136);if(uShape==1||uShape==2){float n=uShape==1?6.:8.;float a=atan(p.y,p.x),sector=2.*PI/n;return length(p)*cos(mod(a,sector)-PI/n)/cos(PI/n);}return length(p);}
vec3 light(vec3 c){vec3 l=linearize(c);return l*(1.+uHighlight*2.*smoothstep(.6,1.,max(max(l.r,l.g),l.b)));}
void main(){
 vec2 p=(uv-.5)/(1.+.02*uBreathing*uFocus)+.5;vec3 original=source(p);if(uOriginal==1){frag=vec4(source(uv),1);return;}float d=texture(uDepth,p).r;if(uShowDepth==1){frag=vec4(vec3(d),1);return;}if(uStrength<.001){frag=vec4(original,1);return;}
 vec4 matte=texture(uForeground,p);float weights=0.,depthSum=0.,hairDepth=0.,hairWeights=0.;
 for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){vec2 cell=floor(p*uMapSize)+vec2(x,y)+.5;vec2 q=clamp(cell/uMapSize,vec2(0),vec2(1));vec2 dist=cell-p*uMapSize;vec3 delta=source(q)-original;float w=exp(-65.*dot(delta,delta)-.65*dot(dist,dist));vec4 m=texture(uForeground,q);float dd=texture(uDepth,q).r;depthSum+=dd*w;weights+=w;float hw=w*m.a*m.a;hairDepth+=dd*hw;hairWeights+=hw;}
 matte.rgb/=max(matte.a,.00001);d=depthSum/weights;if(uPortrait==1&&matte.a>.03&&matte.a<.98&&hairWeights>.01)d=mix(d,hairDepth/hairWeights,.9);
 float r=coc(d);int count=clamp(uSamples,24,96);vec3 sum=vec3(0),nearSum=vec3(0);float total=0.,nearCoverage=0.;float searchR=max(1.,uStrength*.022*min(uSize.x,uSize.y)*(uShape==3?1.4142136:1.));float area=uShape==1?.8269933:(uShape==2?.9003163:1.);
 for(int i=0;i<96;i++){if(i>=count)break;vec2 disk=aperture(i,count);vec2 q=clamp(p+disk*max(r,.2)/uSize,vec2(0),vec2(1));float sd=texture(uDepth,q).r,sr=coc(sd),rho=gauge(disk);float edge=mix(exp(-rho*rho*.9),.65+2.8*smoothstep(.75,1.,rho),uRim);float w=edge*(1.-smoothstep(.02,.08,sd-d)*(1.-smoothstep(.5,2.,sr)));sum+=light(source(q))*w;total+=w;
 // Gather a foreground source's footprint, including spill onto a sharp destination.
 float fi=float(i)+.5,theta=fi*2.39996322973;vec2 off=sqrt(fi/float(count))*vec2(cos(theta),sin(theta))*searchR;vec2 nq=clamp(p+off/uSize,vec2(0),vec2(1));float nd=texture(uDepth,nq).r,nr=coc(nd);float radius=max(nr,.75);float g=gauge(off/radius);float cover=(1.-smoothstep(1.-.7/radius,1.+.7/radius,g))*smoothstep(.02,.09,nd-d)*smoothstep(uBand,uBand+.05,nd-uFocus);float nw=cover*searchR*searchR/(radius*radius*area*float(count));if(nw>.00001){nearSum+=light(source(nq))*nw;nearCoverage+=nw;}
 }
 vec3 result=mix(linearize(original),sum/max(.001,total),smoothstep(.25,1.,r));float alpha=uPortrait==1?matte.a:0.;float sharp=alpha*(1.-smoothstep(uBand,uBand+.05,abs(d-uFocus)))*(1.-smoothstep(.5,2.,r));// Preserve captured color: uncertain RVM foreground RGB can create gray patches.
 vec3 fg=original;result=mix(result,linearize(fg),sharp);if(nearCoverage>.001)result=mix(result,nearSum/nearCoverage,clamp(nearCoverage,0.,1.));frag=vec4(clamp(encode(result),0.,1.),1);
}
