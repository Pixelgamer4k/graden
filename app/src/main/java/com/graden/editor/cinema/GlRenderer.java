// SPDX-License-Identifier: GPL-3.0-only
package com.graden.editor.cinema;

import android.content.Context;
import android.graphics.*;
import android.opengl.*;
import android.view.Surface;
import java.io.*;
import java.nio.*;
import static android.opengl.GLES30.*;

/** One EGL context owned by one worker thread; no full-resolution CPU readback on export. */
public final class GlRenderer implements AutoCloseable {
    private EGLDisplay display;private EGLContext context;private EGLSurface pbuffer,window;
    private EGLConfig config;
    private int copyProgram,dofProgram,externalProgram;
    private int sourceTex,sourceFbo,scratchTex,scratchFbo,depthTex,fgTex,uploadTex;
    private int scratchW,scratchH;private ByteBuffer readback;
    private final int width,height;
    private final FloatBuffer quad=ByteBuffer.allocateDirect(8*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private SurfaceTexture decoderTexture;private Surface decoderSurface;private int oes;
    private final Object frameLock=new Object();private boolean frameReady;
    private final android.os.HandlerThread callbacks=new android.os.HandlerThread("DecoderFrames");
    public GlRenderer(Context c,int w,int h)throws Exception {
        width=w;height=h;quad.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);int[] version=new int[2];
        if(!EGL14.eglInitialize(display,version,0,version,1))throw new IOException("Unable to initialize OpenGL.");
        EGLConfig[] configs=new EGLConfig[1];int[] n=new int[1];
        int[] attributes={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,0x40,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT|EGL14.EGL_PBUFFER_BIT,0x3142,1,EGL14.EGL_NONE};
        if(!EGL14.eglChooseConfig(display,attributes,0,configs,0,1,n,0)||n[0]==0)throw new IOException("OpenGL ES 3 encoder surfaces are unavailable.");
        config=configs[0];context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,3,EGL14.EGL_NONE},0);
        pbuffer=EGL14.eglCreatePbufferSurface(display,config,new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
        makeCurrent(pbuffer);
        String vs=read(c,"cinema/fullscreen.vert");copyProgram=program(vs,read(c,"cinema/copy.frag"));dofProgram=program(vs,read(c,"cinema/dof.frag"));externalProgram=program(vs,read(c,"cinema/external.frag"));
        sourceTex=texture(w,h);sourceFbo=fbo(sourceTex);uploadTex=texture(1,1);depthTex=texture(1,1);fgTex=texture(1,1);
        callbacks.start();check("initialize");
    }
    private static String read(Context c,String name)throws IOException {try(InputStream in=c.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");}}
    private static int shader(int type,String code){int s=glCreateShader(type);glShaderSource(s,code);glCompileShader(s);int[] ok=new int[1];glGetShaderiv(s,GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException(glGetShaderInfoLog(s));return s;}
    private static int program(String vs,String fs){int v=shader(GL_VERTEX_SHADER,vs),f=shader(GL_FRAGMENT_SHADER,fs),p=glCreateProgram();glAttachShader(p,v);glAttachShader(p,f);glLinkProgram(p);int[] ok=new int[1];glGetProgramiv(p,GL_LINK_STATUS,ok,0);glDeleteShader(v);glDeleteShader(f);if(ok[0]==0)throw new IllegalStateException(glGetProgramInfoLog(p));return p;}
    private static int texture(int w,int h){int[] a=new int[1];glGenTextures(1,a,0);glBindTexture(GL_TEXTURE_2D,a[0]);params(GL_TEXTURE_2D);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,null);return a[0];}
    private static void params(int type){glTexParameteri(type,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(type,GL_TEXTURE_MAG_FILTER,GL_LINEAR);glTexParameteri(type,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(type,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);}
    private static int fbo(int tex){int[] a=new int[1];glGenFramebuffers(1,a,0);glBindFramebuffer(GL_FRAMEBUFFER,a[0]);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,tex,0);if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Framebuffer allocation failed");return a[0];}
    private void makeCurrent(EGLSurface surface){if(!EGL14.eglMakeCurrent(display,surface,surface,context))throw new IllegalStateException("EGL context unavailable: "+EGL14.eglGetError());}
    private static void bind(int program,String name,int texture,int unit,int target){glActiveTexture(GL_TEXTURE0+unit);glBindTexture(target,texture);glUniform1i(glGetUniformLocation(program,name),unit);}
    private void draw(int p,int target,int w,int h){glBindFramebuffer(GL_FRAMEBUFFER,target);glViewport(0,0,w,h);int pos=glGetAttribLocation(p,"aPosition");quad.position(0);glEnableVertexAttribArray(pos);glVertexAttribPointer(pos,2,GL_FLOAT,false,0,quad);glDrawArrays(GL_TRIANGLE_STRIP,0,4);glDisableVertexAttribArray(pos);check("render");}
    private static void upload(int tex,Bitmap bitmap){upload(tex,bitmap,false);}
    public static void upload(int tex,Bitmap bitmap,boolean premultiply){
        int w=bitmap.getWidth(),h=bitmap.getHeight();int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);
        ByteBuffer bytes=ByteBuffer.allocateDirect(w*h*4);for(int p:pixels){int a=p>>>24,r=p>>16&255,g=p>>8&255,b=p&255;if(premultiply){r=(r*a+127)/255;g=(g*a+127)/255;b=(b*a+127)/255;}bytes.put((byte)r);bytes.put((byte)g);bytes.put((byte)b);bytes.put((byte)a);}bytes.flip();
        glBindTexture(GL_TEXTURE_2D,tex);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,bytes);
    }
    public void setBitmap(Bitmap b){makeCurrent(pbuffer);glActiveTexture(GL_TEXTURE0);upload(uploadTex,b);glUseProgram(copyProgram);bind(copyProgram,"uImage",uploadTex,0,GL_TEXTURE_2D);glUniform1i(glGetUniformLocation(copyProgram,"uFlip"),0);draw(copyProgram,sourceFbo,width,height);}
    public Surface decoderSurface(){
        int[] a=new int[1];glGenTextures(1,a,0);oes=a[0];glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,oes);params(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
        decoderTexture=new SurfaceTexture(oes);decoderTexture.setOnFrameAvailableListener(t->{synchronized(frameLock){frameReady=true;frameLock.notifyAll();}},new android.os.Handler(callbacks.getLooper()));
        decoderSurface=new Surface(decoderTexture);return decoderSurface;
    }
    public void awaitFrame(int rotation)throws Exception {
        long until=android.os.SystemClock.elapsedRealtime()+15000;
        synchronized(frameLock){while(!frameReady){long remaining=until-android.os.SystemClock.elapsedRealtime();if(remaining<=0)throw new IOException("Timed out waiting for a decoded frame.");frameLock.wait(remaining);}frameReady=false;}
        makeCurrent(pbuffer);decoderTexture.updateTexImage();float[] m=new float[16];decoderTexture.getTransformMatrix(m);
        glUseProgram(externalProgram);bind(externalProgram,"uImage",oes,0,GLES11Ext.GL_TEXTURE_EXTERNAL_OES);glUniformMatrix4fv(glGetUniformLocation(externalProgram,"uTransform"),1,false,m,0);glUniform1i(glGetUniformLocation(externalProgram,"uRotation"),rotation);draw(externalProgram,sourceFbo,width,height);
    }
    private void scratch(int w,int h){if(scratchW==w&&scratchH==h)return;if(scratchTex!=0){glDeleteTextures(1,new int[]{scratchTex},0);glDeleteFramebuffers(1,new int[]{scratchFbo},0);}scratchTex=texture(w,h);scratchFbo=fbo(scratchTex);scratchW=w;scratchH=h;}
    public Bitmap analysisBitmap(int edge){makeCurrent(pbuffer);float scale=Math.min(1f,edge/(float)Math.max(width,height));int w=Math.max(2,Math.round(width*scale)),h=Math.max(2,Math.round(height*scale));scratch(w,h);glUseProgram(copyProgram);bind(copyProgram,"uImage",sourceTex,0,GL_TEXTURE_2D);glUniform1i(glGetUniformLocation(copyProgram,"uFlip"),1);draw(copyProgram,scratchFbo,w,h);if(readback==null||readback.capacity()!=w*h*4)readback=ByteBuffer.allocateDirect(w*h*4);readback.rewind();glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,readback);Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);AiEngine.nFlip(readback,b);return b;}
    public void setMaps(AiEngine.Maps maps){makeCurrent(pbuffer);glActiveTexture(GL_TEXTURE0);upload(depthTex,maps.depth);upload(fgTex,maps.foreground,true);}
    private void render(AiEngine.Maps maps,Settings s,int target,int w,int h){glUseProgram(dofProgram);bind(dofProgram,"uImage",sourceTex,0,GL_TEXTURE_2D);bind(dofProgram,"uDepth",depthTex,1,GL_TEXTURE_2D);bind(dofProgram,"uForeground",fgTex,2,GL_TEXTURE_2D);
        glUniform2f(glGetUniformLocation(dofProgram,"uSize"),w,h);glUniform2f(glGetUniformLocation(dofProgram,"uMapSize"),s.portrait?maps.foreground.getWidth():maps.width,s.portrait?maps.foreground.getHeight():maps.height);
        glUniform1f(glGetUniformLocation(dofProgram,"uFocus"),s.focus);glUniform1f(glGetUniformLocation(dofProgram,"uStrength"),s.strength);glUniform1f(glGetUniformLocation(dofProgram,"uBand"),s.band);
        glUniform1f(glGetUniformLocation(dofProgram,"uRotation"),s.rotation);glUniform1f(glGetUniformLocation(dofProgram,"uHighlight"),s.highlight);glUniform1f(glGetUniformLocation(dofProgram,"uRim"),s.rim);glUniform1f(glGetUniformLocation(dofProgram,"uBreathing"),s.breathing);glUniform1i(glGetUniformLocation(dofProgram,"uShape"),s.shape);glUniform1i(glGetUniformLocation(dofProgram,"uSamples"),s.samples);
        glUniform1i(glGetUniformLocation(dofProgram,"uPortrait"),s.portrait?1:0);glUniform1i(glGetUniformLocation(dofProgram,"uShowDepth"),s.showDepth?1:0);glUniform1i(glGetUniformLocation(dofProgram,"uOriginal"),s.original?1:0);draw(dofProgram,target,w,h);
    }
    public Bitmap preview(AiEngine.Maps maps,Settings settings){makeCurrent(pbuffer);scratch(width,height);render(maps,settings,scratchFbo,width,height);return readPixels(width,height);}
    public void attachEncoder(Surface surface){window=EGL14.eglCreateWindowSurface(display,config,surface,new int[]{EGL14.EGL_NONE},0);if(window==EGL14.EGL_NO_SURFACE)throw new IllegalStateException("Cannot create encoder EGL surface");}
    public void encode(AiEngine.Maps maps,Settings s,long ptsUs){makeCurrent(window);render(maps,s,0,width,height);EGLExt.eglPresentationTimeANDROID(display,window,ptsUs*1000);if(!EGL14.eglSwapBuffers(display,window))throw new IllegalStateException("Encoder surface rejected a frame");makeCurrent(pbuffer);}
    private static Bitmap readPixels(int w,int h){ByteBuffer b=ByteBuffer.allocateDirect(w*h*4);glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,b);int[] px=new int[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=((h-1-y)*w+x)*4;px[y*w+x]=0xff000000|(b.get(i)&255)<<16|(b.get(i+1)&255)<<8|(b.get(i+2)&255);}return Bitmap.createBitmap(px,w,h,Bitmap.Config.ARGB_8888);}
    private static void check(String stage){int error=glGetError();if(error!=GL_NO_ERROR)throw new IllegalStateException("OpenGL "+stage+": "+error);}
    public void close(){
        if(decoderSurface!=null)decoderSurface.release();if(decoderTexture!=null)decoderTexture.release();callbacks.quitSafely();
        if(display!=null&&display!=EGL14.EGL_NO_DISPLAY){EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(window!=null)EGL14.eglDestroySurface(display,window);if(pbuffer!=null)EGL14.eglDestroySurface(display,pbuffer);if(context!=null)EGL14.eglDestroyContext(display,context);EGL14.eglTerminate(display);EGL14.eglReleaseThread();}
    }
}
