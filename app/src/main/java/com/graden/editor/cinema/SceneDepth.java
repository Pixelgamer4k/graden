// SPDX-License-Identifier: GPL-3.0-only
package com.graden.editor.cinema;
import java.util.Arrays;
/** Align relative depth to scene history instead of renormalizing every frame. */
public final class SceneDepth {
 private int[] lastRgb;private float[] lastDepth;private int width,height;private float scale,offset;public int scene=-1;public boolean cut;
 public void reset(){lastRgb=null;lastDepth=null;scene=-1;}
 static float color(int a,int b){return (Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))+Math.abs((a&255)-(b&255)))/765f;}
 public float[] update(float[] raw,int[] rgb,int w,int h){for(float x:raw)if(!Float.isFinite(x))throw new IllegalArgumentException("Invalid model depth");boolean first=lastRgb==null||w!=width||h!=height;int dx=0,dy=0;float best=1;float hist=0;
 if(!first){int[] a=new int[48],b=new int[48];int n=0;for(int i=0;i<rgb.length;i+=13){for(int c=0;c<3;c++){a[c*16+((rgb[i]>>(c*8))&255)/16]++;b[c*16+((lastRgb[i]>>(c*8))&255)/16]++;}n++;}for(int i=0;i<48;i++)hist+=Math.abs(a[i]-b[i]);hist/=Math.max(1,6*n);
 for(int yy=-16;yy<=16;yy+=4)for(int xx=-16;xx<=16;xx+=4){float sum=0;int count=0;for(int y=18;y<h-18;y+=8)for(int x=18;x<w-18;x+=8){sum+=color(rgb[y*w+x],lastRgb[(y+yy)*w+x+xx]);count++;}float err=sum/Math.max(1,count);if(err<best){best=err;dx=xx;dy=yy;}}}
 cut=first||best>.25f||(hist>.42f&&best>.15f);if(cut){scene++;float[] sorted=raw.clone();Arrays.sort(sorted);float lo=sorted[(int)(raw.length*.02)],hi=sorted[(int)(raw.length*.98)];scale=.8f/Math.max(.001f,hi-lo);offset=.1f-lo*scale;}
 else{double sx=0,sy=0,sxx=0,sxy=0;int count=0;for(int y=18;y<h-18;y+=4)for(int x=18;x<w-18;x+=4){int i=y*w+x,j=(y+dy)*w+x+dx;float d=lastDepth[j];if(color(rgb[i],lastRgb[j])>.07||d<.03||d>.97)continue;double r=raw[i];sx+=r;sy+=d;sxx+=r*r;sxy+=r*d;count++;}double den=count*sxx-sx*sx;if(count>80&&den>1e-7){float s=(float)((count*sxy-sx*sy)/den),o=(float)((sy-s*sx)/count);if(s>scale*.25&&s<scale*4){scale=s;offset=o;}}}
 float[] out=new float[raw.length];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=y*w+x;float d=Math.max(0,Math.min(1,raw[i]*scale+offset));int xx=x+dx,yy=y+dy;if(!cut&&xx>=0&&xx<w&&yy>=0&&yy<h){int j=yy*w+xx;if(color(rgb[i],lastRgb[j])<.035&&Math.abs(d-lastDepth[j])<.08)d=.8f*d+.2f*lastDepth[j];}out[i]=d;}lastRgb=rgb.clone();lastDepth=out.clone();width=w;height=h;return out;
 }
}
