// SPDX-License-Identifier: GPL-3.0-only
package com.graden.editor.cinema;
import android.content.Context;import android.net.Uri;import android.media.*;import android.graphics.Bitmap;import java.nio.ByteBuffer;import java.util.concurrent.atomic.AtomicBoolean;import java.io.IOException;
public final class ClipAnalyzer {
 public interface Progress{void update(int percent,String message);}
 public static void run(Context c,Uri input,Settings s,AtomicBoolean cancel,Progress progress)throws Exception{ClipCache cache=new ClipCache(c,input);cache.begin();VideoInfo info=VideoInfo.read(c,input);int[] size=info.outputSize(s.analysisEdge);MediaExtractor ex=new MediaExtractor();MediaCodec decoder=null;Pipeline pipe=null;try(AiEngine ai=new AiEngine(c);GlRenderer gl=new GlRenderer(c,size[0],size[1])){ex.setDataSource(c,input,null);ex.selectTrack(info.videoTrack);MediaFormat fmt=ex.getTrackFormat(info.videoTrack);fmt.setInteger(MediaFormat.KEY_ROTATION,0);decoder=MediaCodec.createDecoderByType(info.mime);decoder.configure(fmt,gl.decoderSurface(),null,0);decoder.start();boolean fed=false,done=false;long first=-1,last=-1,active=android.os.SystemClock.elapsedRealtime();MediaCodec.BufferInfo b=new MediaCodec.BufferInfo();pipe=new Pipeline(ai,cache,info,cancel,progress);while(!done){if(cancel.get())throw new InterruptedException("Analysis cancelled");boolean advanced=false;if(!fed){int ix=decoder.dequeueInputBuffer(1000);if(ix>=0){ByteBuffer buf=decoder.getInputBuffer(ix);int n=ex.readSampleData(buf,0);if(n<0){decoder.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);fed=true;}else{decoder.queueInputBuffer(ix,0,n,ex.getSampleTime(),0);ex.advance();}advanced=true;}}int ix=decoder.dequeueOutputBuffer(b,10000);if(ix>=0){boolean frame=b.size>0;long pts=b.presentationTimeUs;done=(b.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;decoder.releaseOutputBuffer(ix,frame);if(frame){gl.awaitFrame(info.rotation);if(first<0)first=pts;if(pts<last)throw new IOException("Non-monotonic video timestamps");Bitmap image=gl.analysisBitmap(s.analysisEdge);last=pts;pipe.put(image,pts-first);}advanced=true;}else if(ix==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)advanced=true;if(advanced)active=android.os.SystemClock.elapsedRealtime();else if(android.os.SystemClock.elapsedRealtime()-active>30000)throw new IOException("Decoder timed out");}if(cancel.get())throw new InterruptedException("Analysis cancelled");pipe.finish();cache.finish();}finally{if(pipe!=null)pipe.stop();if(decoder!=null){try{decoder.stop();}catch(Exception ignored){}decoder.release();}ex.release();}}

 /** Frames flow through depth, then matting, then storage. Each stage keeps frame order; stages overlap on different frames. */
 private static final class Pipeline{
  private static final class Job{Bitmap image;long time;AiEngine.Maps maps;}
  private static final Job END=new Job();
  private final java.util.concurrent.ArrayBlockingQueue<Job> toDepth=new java.util.concurrent.ArrayBlockingQueue<>(2),toDetect=new java.util.concurrent.ArrayBlockingQueue<>(2),toMatte=new java.util.concurrent.ArrayBlockingQueue<>(2);
  private final java.util.concurrent.ExecutorService store=java.util.concurrent.Executors.newFixedThreadPool(3);
  private final java.util.concurrent.Semaphore room=new java.util.concurrent.Semaphore(4);
  private final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
  private final Thread depth,detect,matte;private volatile boolean stopped;
  Pipeline(AiEngine ai,ClipCache cache,VideoInfo info,AtomicBoolean cancel,Progress progress){
   depth=new Thread(()->{try{while(true){Job j=toDepth.take();if(j==END){toDetect.put(END);return;}j.maps=ai.depthStage(j.image);toDetect.put(j);}}catch(Throwable e){fail(e);}},"depth");
   detect=new Thread(()->{try{while(true){Job j=toDetect.take();if(j==END){toMatte.put(END);return;}ai.detectStage(j.image,j.maps);toMatte.put(j);}}catch(Throwable e){fail(e);}},"detect");
   matte=new Thread(()->{try{while(true){Job j=toMatte.take();if(j==END)return;ai.matteStage(j.image,true,j.maps);int ix=cache.reserve(j.time,j.maps.scene);room.acquire();store.execute(()->{try{cache.write(ix,j.image,j.maps);}catch(Throwable e){fail(e);}finally{j.maps.close();j.image.recycle();room.release();}});progress.update((int)Math.min(99,j.time*100/Math.max(1,info.durationUs)),"Analysis frame "+(ix+1)+" · scene "+(j.maps.scene+1));}}catch(Throwable e){fail(e);}},"matte");
   depth.start();detect.start();matte.start();
  }
  private void fail(Throwable e){failure.compareAndSet(null,e);stopped=true;depth.interrupt();detect.interrupt();matte.interrupt();}
  void check()throws Exception{Throwable e=failure.get();if(e!=null){if(e instanceof Exception)throw (Exception)e;throw new IOException(e);}}
  void put(Bitmap image,long time)throws Exception{Job j=new Job();j.image=image;j.time=time;while(!toDepth.offer(j,50,java.util.concurrent.TimeUnit.MILLISECONDS))check();check();}
  void finish()throws Exception{while(!toDepth.offer(END,50,java.util.concurrent.TimeUnit.MILLISECONDS))check();depth.join();detect.join();matte.join();store.shutdown();store.awaitTermination(2,java.util.concurrent.TimeUnit.MINUTES);check();}
  void stop(){if(depth.isAlive()||detect.isAlive()||matte.isAlive()){depth.interrupt();detect.interrupt();matte.interrupt();}store.shutdownNow();try{depth.join(3000);detect.join(3000);matte.join(3000);store.awaitTermination(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}}
 }
}
