// SPDX-License-Identifier: GPL-3.0-only
package com.graden.editor.cinema;

import org.tensorflow.lite.*;
import android.content.Context;
import android.graphics.Bitmap;
import java.io.*;
import java.nio.*;
import java.util.*;

/** The original engine, with its two model runs moved to the Hexagon NPU. All logic around them is unchanged. */
public final class AiEngine implements AutoCloseable {
    static{System.loadLibrary("depthnpu");}
    /** True once a model has opened on the NPU in this run; false on the CPU. */
    public static volatile boolean onNpu;
    private static native long nOpen(String dir,String hexagon,String cache,String token);
    private static native void nClose(long handle);
    private static native void nRgb(Bitmap b,ByteBuffer to);
    private static native void nPack(ByteBuffer alpha,ByteBuffer fg,Bitmap to);
    static native void nFlip(ByteBuffer rgba,Bitmap to);
    private static final int DEPTH=518;
    private final Context context;
    private Npu depth,detector;private Matte matte;private float[] lastBoxes=new float[0];private int detectClock;
    private static final int DETECT=640;
    /** YOLOX (Apache-2.0), every third frame: people, animals and everyday objects. Between runs the last boxes stand. */
    private float[] detect(Bitmap input)throws Exception{
        if(detector==null)detector=new Npu("yolox.tflite");
        Bitmap b=Bitmap.createScaledBitmap(input,DETECT,DETECT,true);ByteBuffer in=ByteBuffer.allocateDirect(DETECT*DETECT*12).order(ByteOrder.nativeOrder());rgb(b,in);if(b!=input)b.recycle();in.rewind();
        int n=detector.run.getOutputTensor(0).shape()[1];ByteBuffer box=null,score=null,cls=null;HashMap<Integer,Object> out=new HashMap<>();
        for(int i=0;i<detector.run.getOutputTensorCount();i++){Tensor t=detector.run.getOutputTensor(i);ByteBuffer x=ByteBuffer.allocateDirect(t.numBytes()).order(ByteOrder.nativeOrder());out.put(i,x);if(t.shape().length==3)box=x;else if(t.numBytes()==n)cls=x;else score=x;}
        detector.run.runForMultipleInputsOutputs(new Object[]{in},out);box.rewind();score.rewind();cls.rewind();
        FloatBuffer bf=box.asFloatBuffer(),sf=score.asFloatBuffer();ArrayList<float[]> found=new ArrayList<>();
        for(int i=0;i<n;i++){float c=sf.get(i);if(c<((cls.get(i)&255)==0?.4f:.6f))continue;found.add(new float[]{cls.get(i)&255,c,bf.get(i*4)/DETECT,bf.get(i*4+1)/DETECT,bf.get(i*4+2)/DETECT,bf.get(i*4+3)/DETECT});}
        found.sort((p,q)->Float.compare(q[1],p[1]));ArrayList<float[]> keep=new ArrayList<>();
        for(float[] a:found){boolean clash=false;for(float[] k:keep){float w=Math.min(a[4],k[4])-Math.max(a[2],k[2]),h=Math.min(a[5],k[5])-Math.max(a[3],k[3]);if(w<=0||h<=0)continue;float both=w*h,all=(a[4]-a[2])*(a[5]-a[3])+(k[4]-k[2])*(k[5]-k[3])-both;if(k[0]==a[0]&&both/all>.5f){clash=true;break;}}if(!clash&&keep.size()<12)keep.add(a);}
        float[] r=new float[keep.size()*6];for(int i=0;i<keep.size();i++)System.arraycopy(keep.get(i),0,r,i*6,6);return r;
    }
    /** One model on the NPU; on the CPU if the NPU can't be opened. */
    private final class Npu implements AutoCloseable {
        long handle;final Interpreter run;
        Npu(String model)throws Exception{
            File f=model.startsWith("rvm_")?new File(asset(context,model)):com.graden.editor.CinemaModels.model(context,model);Interpreter.Options o=new Interpreter.Options();
            String v=com.graden.editor.CinemaModels.hexagon;
            if(v!=null)try{handle=nOpen(com.graden.editor.CinemaModels.qnnDir(context).getPath(),v,context.getCacheDir().getPath(),model.replace(".tflite",""));}catch(Throwable e){android.util.Log.w("Cinema","no NPU",e);}
            if(handle!=0){final long h=handle;o.addDelegate(new Delegate(){public long getNativeHandle(){return h;}});onNpu=true;}else o.setNumThreads(Math.max(2,Math.min(7,Runtime.getRuntime().availableProcessors()-1)));
            run=new Interpreter(f,o);
        }
        public void close(){run.close();if(handle!=0)nClose(handle);handle=0;}
    }
    /** RVM at one fixed shape, keeping its recurrent memory between frames as the original does. */
    private final class Matte implements AutoCloseable {
        final int w,h;final Npu net;final ByteBuffer src,pha,fgr;final Object[] in;final HashMap<Integer,Object> out=new HashMap<>();final HashMap<Integer,Integer> state=new HashMap<>();
        Matte(int w,int h,String model)throws Exception{
            this.w=w;this.h=h;net=new Npu(model);
            src=ByteBuffer.allocateDirect(w*h*12).order(ByteOrder.nativeOrder());pha=ByteBuffer.allocateDirect(w*h*4).order(ByteOrder.nativeOrder());fgr=ByteBuffer.allocateDirect(w*h*12).order(ByteOrder.nativeOrder());
            in=new Object[net.run.getInputTensorCount()];
            for(int i=0;i<in.length;i++){Tensor t=net.run.getInputTensor(i);in[i]=t.shape()[3]==3?src:ByteBuffer.allocateDirect(t.numBytes()).order(ByteOrder.nativeOrder());}
            for(int i=0;i<net.run.getOutputTensorCount();i++){Tensor t=net.run.getOutputTensor(i);int c=t.shape()[3];if(c==1)out.put(i,pha);else if(c==3)out.put(i,fgr);else{out.put(i,ByteBuffer.allocateDirect(t.numBytes()).order(ByteOrder.nativeOrder()));for(int j=0;j<in.length;j++)if(Arrays.equals(net.run.getInputTensor(j).shape(),t.shape()))state.put(i,j);}}
        }
        void forget(){for(int j:state.values()){ByteBuffer b=(ByteBuffer)in[j];b.clear();while(b.hasRemaining())b.put((byte)0);b.rewind();}}
        void run(){src.rewind();pha.rewind();fgr.rewind();for(Object b:in)((ByteBuffer)b).rewind();for(Object b:out.values())((ByteBuffer)b).rewind();net.run.runForMultipleInputsOutputs(in,out);for(Map.Entry<Integer,Integer> e:state.entrySet()){Object x=in[e.getValue()];in[e.getValue()]=out.get(e.getKey());out.put(e.getKey(),x);}pha.rewind();fgr.rewind();}
        public void close(){net.close();}
    }
    private float[] previousDepth;
    private int[] previousRgb;
    private final SceneDepth sceneDepth=new SceneDepth();
    public static final class Maps implements AutoCloseable {
        public Bitmap depth,foreground;
        public float[] values;
        public int width,height;
        public float personDepth=.5f;public int scene;public boolean cut;
        /** What was detected: class, score, left, top, right, bottom per object, in 0..1 of the frame. */
        public float[] boxes=new float[0];
        public float at(float x,float y){int xx=Math.max(0,Math.min(width-1,(int)(x*width)));int yy=Math.max(0,Math.min(height-1,(int)(y*height)));return values[yy*width+xx];}
        public boolean personAt(float x,float y){int xx=Math.max(0,Math.min(foreground.getWidth()-1,(int)(x*foreground.getWidth())));int yy=Math.max(0,Math.min(foreground.getHeight()-1,(int)(y*foreground.getHeight())));return (foreground.getPixel(xx,yy)>>>24)>150;}
        public void close(){if(depth!=null)depth.recycle();if(foreground!=null)foreground.recycle();}
    }
    public AiEngine(Context context) throws Exception {
        this.context=context;
        depth=new Npu("depth.tflite");
    }
    private Matte matteFor(Bitmap b)throws Exception{
        boolean tall=b.getHeight()>b.getWidth();int m=Math.max(b.getWidth(),b.getHeight());int edge=m>1500?1920:m>1100?1280:960,shortEdge=edge*9/16;
        int w=tall?shortEdge:edge,h=tall?edge:shortEdge;
        if(matte==null||matte.w!=w||matte.h!=h){if(matte!=null)matte.close();matte=new Matte(w,h,"rvm_"+edge+(tall?"p":"l")+".tflite");}
        return matte;
    }
    private static void rgb(Bitmap b,ByteBuffer to){nRgb(b,to);}
    private String asset(Context c,String name)throws IOException {
        File dest=new File(c.getFilesDir(),"v1-"+name);
        if(!dest.exists()) {
            File tmp=new File(dest+".part");
            try(InputStream in=c.getAssets().open("cinema/"+name);OutputStream out=new FileOutputStream(tmp)) {byte[] b=new byte[131072];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            if(!tmp.renameTo(dest))throw new IOException("Unable to install the bundled AI model.");
        }
        return dest.getAbsolutePath();
    }
    public void reset(){if(matte!=null)matte.forget();previousDepth=null;previousRgb=null;sceneDepth.reset();}
    static float clamp(float x){return Math.max(0,Math.min(1,x));}
    public Maps analyze(Bitmap input,boolean hair) throws Exception {Maps m=depthStage(input);try{detectStage(input,m);matteStage(input,hair,m);}catch(Exception ex){m.close();throw ex;}return m;}
    /** Depth and scene tracking. Runs strictly in frame order; a pipeline may overlap it with the matte stage of an earlier frame. */
    public Maps depthStage(Bitmap input) throws Exception {
        int dw=Math.max(14,Math.round(input.getWidth()*518f/Math.max(input.getWidth(),input.getHeight())/14)*14);
        int dh=Math.max(14,Math.round(input.getHeight()*518f/Math.max(input.getWidth(),input.getHeight())/14)*14);
        Bitmap small=Bitmap.createScaledBitmap(input,dw,dh,true);
        int[] rgb=new int[dw*dh];small.getPixels(rgb,0,dw,0,0,dw,dh);
        float[] values;
        {   // The NPU model takes a fixed 518 square; its answer is resampled to the original's size.
            Bitmap square=Bitmap.createScaledBitmap(input,DEPTH,DEPTH,true);ByteBuffer in=ByteBuffer.allocateDirect(DEPTH*DEPTH*12).order(ByteOrder.nativeOrder()),out=ByteBuffer.allocateDirect(DEPTH*DEPTH*4).order(ByteOrder.nativeOrder());
            rgb(square,in);if(square!=input)square.recycle();in.rewind();depth.run.run(in,out);out.rewind();float[] raw=new float[DEPTH*DEPTH];out.asFloatBuffer().get(raw);values=new float[dw*dh];
            for(int y=0;y<dh;y++){float sy=(y+.5f)/dh*DEPTH-.5f;int y0=Math.max(0,(int)Math.floor(sy)),y1=Math.min(DEPTH-1,y0+1);float fy=Math.max(0,sy-y0);for(int x=0;x<dw;x++){float sx=(x+.5f)/dw*DEPTH-.5f;int x0=Math.max(0,(int)Math.floor(sx)),x1=Math.min(DEPTH-1,x0+1);float fx=Math.max(0,sx-x0);values[y*dw+x]=(raw[y0*DEPTH+x0]*(1-fx)+raw[y0*DEPTH+x1]*fx)*(1-fy)+(raw[y1*DEPTH+x0]*(1-fx)+raw[y1*DEPTH+x1]*fx)*fy;}}
        }
        if(small!=input)small.recycle();
        values=sceneDepth.update(values,rgb,dw,dh);int[] depthPx=new int[values.length];for(int i=0;i<values.length;i++){int q=Math.round(values[i]*255);depthPx[i]=0xff000000|q<<16|q<<8|q;}
        Maps result=new Maps();result.width=dw;result.height=dh;result.values=values;result.scene=sceneDepth.scene;result.cut=sceneDepth.cut;
        result.depth=Bitmap.createBitmap(depthPx,dw,dh,Bitmap.Config.ARGB_8888);
        return result;
    }
    /** Matting, in frame order, after that frame's depth stage. The original's cut reset happens here, on the same frame. */
    /** Object detection, in frame order: its own pipeline stage, so it never holds up depth or matting. */
    public void detectStage(Bitmap input,Maps result) throws Exception {if(detectClock++%3==0||result.cut)lastBoxes=detect(input);result.boxes=lastBoxes;}
    public void matteStage(Bitmap input,boolean hair,Maps result) throws Exception {
        if(result.cut&&matte!=null)matte.forget();
        int dw=result.width,dh=result.height;float[] values=result.values;
        if(hair) {
            try {
                Matte mt=matteFor(input);Bitmap sized=input.getWidth()==mt.w&&input.getHeight()==mt.h?input:Bitmap.createScaledBitmap(input,mt.w,mt.h,true);rgb(sized,mt.src);if(sized!=input)sized.recycle();mt.run();result.foreground=Bitmap.createBitmap(mt.w,mt.h,Bitmap.Config.ARGB_8888);
                // Preserve unassociated foreground colour for GL composition.
                result.foreground.setPremultiplied(false);nPack(mt.pha,mt.fgr,result.foreground);FloatBuffer alpha=mt.pha.asFloatBuffer();
                float[] person=new float[values.length];int count=0;
                for(int y=0;y<dh;y+=2)for(int x=0;x<dw;x+=2){int ai=(y*mt.h/dh)*mt.w+x*mt.w/dw;if(alpha.get(ai)>.95f)person[count++]=values[y*dw+x];}
                if(count>0){Arrays.sort(person,0,count);result.personDepth=person[count/2];}
                }finally{}
        } else {
            result.foreground=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);
        }
    }
    private static float distance(int a,int b){return (Math.abs((a>>16&255)-(b>>16&255))+Math.abs((a>>8&255)-(b>>8&255))+Math.abs((a&255)-(b&255)))/765f;}
    public void close(){previousDepth=null;previousRgb=null;sceneDepth.reset();depth.close();if(detector!=null)detector.close();if(matte!=null)matte.close();}
}
