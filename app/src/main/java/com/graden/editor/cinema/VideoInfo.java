// SPDX-License-Identifier: GPL-3.0-only
package com.graden.editor.cinema;
import android.content.Context;
import android.media.*;
import android.net.Uri;

public final class VideoInfo {
    public int width,height,rotation,videoTrack=-1,audioTrack=-1;
    public long durationUs;
    public float fps=30;
    public MediaFormat video,audio;
    public String mime;
    static int integer(MediaFormat f,String key,int fallback) { return f.containsKey(key)?f.getInteger(key):fallback; }
    public static VideoInfo read(Context c, Uri uri) throws Exception {
        MediaExtractor e=new MediaExtractor();
        try {
            e.setDataSource(c,uri,null); VideoInfo v=new VideoInfo();
            for(int i=0;i<e.getTrackCount();i++) {
                MediaFormat f=e.getTrackFormat(i); String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("video/")&&v.videoTrack<0){v.video=f;v.videoTrack=i;v.mime=mime;}
                if(mime!=null&&mime.startsWith("audio/")&&v.audioTrack<0){v.audio=f;v.audioTrack=i;}
            }
            if(v.video==null)throw new Exception("No video track was found.");
            if(!v.mime.equals("video/avc")&&!v.mime.equals("video/hevc"))throw new Exception("Please choose an H.264 or HEVC video.");
            int transfer=integer(v.video,MediaFormat.KEY_COLOR_TRANSFER,3);
            int standard=integer(v.video,MediaFormat.KEY_COLOR_STANDARD,1);
            if(transfer==6||transfer==7||standard==6)throw new Exception("This build supports SDR video only. Convert HDR / HLG / Dolby Vision to SDR first.");
            v.width=v.video.getInteger(MediaFormat.KEY_WIDTH);v.height=v.video.getInteger(MediaFormat.KEY_HEIGHT);
            v.rotation=integer(v.video,MediaFormat.KEY_ROTATION,0);
            if(v.video.containsKey(MediaFormat.KEY_DURATION))v.durationUs=v.video.getLong(MediaFormat.KEY_DURATION);
            try {v.fps=v.video.getNumber(MediaFormat.KEY_FRAME_RATE).floatValue();}catch(Exception ignored){}
            if(v.fps<=0||v.fps>240)v.fps=30;
            if(v.durationUs<=0)throw new Exception("Video duration is unavailable.");
            return v;
        }finally{e.release();}
    }
    public int displayWidth(){return rotation%180==0?width:height;}
    public int displayHeight(){return rotation%180==0?height:width;}
    public int[] outputSize(int cap) {
        float scale=Math.min(1f,cap/(float)Math.max(displayWidth(),displayHeight()));
        return new int[]{Math.max(2,(int)(displayWidth()*scale/2)*2),Math.max(2,(int)(displayHeight()*scale/2)*2)};
    }
}
