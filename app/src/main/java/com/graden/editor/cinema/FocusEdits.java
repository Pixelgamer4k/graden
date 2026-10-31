// SPDX-License-Identifier: GPL-3.0-only
package com.graden.editor.cinema;
import java.io.*;import java.util.*;import org.json.*;
public final class FocusEdits {
 public static final int AUTO=0,TRACK=1,FIXED=2;public float duration=.8f;public final ArrayList<Key> keys=new ArrayList<>();
 public static class Key{public boolean strong=true;public float outHandle=.0f,inHandle=1f;public long time;public int mode;public float x,y,depth,seconds;public Key(long t,int m,float x,float y,float d,float s){time=t;mode=m;this.x=x;this.y=y;depth=d;seconds=s;}}
 public void put(Key k){keys.removeIf(a->Math.abs(a.time-k.time)<1000);keys.add(k);keys.sort(Comparator.comparingLong(a->a.time));}
 public FocusEdits copy(){FocusEdits p=new FocusEdits();p.duration=duration;for(Key k:keys){Key c=new Key(k.time,k.mode,k.x,k.y,k.depth,k.seconds);c.strong=k.strong;c.outHandle=k.outHandle;c.inHandle=k.inHandle;p.put(c);}return p;}
 public void save(File f)throws Exception{JSONObject j=new JSONObject();j.put("duration",duration);JSONArray a=new JSONArray();for(Key k:keys){JSONObject o=new JSONObject();o.put("time",k.time);o.put("mode",k.mode);o.put("x",k.x);o.put("y",k.y);o.put("depth",k.depth);o.put("seconds",k.seconds);o.put("strong",k.strong);o.put("outHandle",k.outHandle);o.put("inHandle",k.inHandle);a.put(o);}j.put("keys",a);File tmp=new File(f+".part");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(j.toString().getBytes("UTF-8"));}if(!tmp.renameTo(f))throw new IOException("Cannot save focus edits");}
 public static FocusEdits load(File f)throws Exception{FocusEdits p=new FocusEdits();if(!f.exists())return p;JSONObject j=new JSONObject(new String(java.nio.file.Files.readAllBytes(f.toPath()),"UTF-8"));p.duration=(float)j.optDouble("duration",.8);JSONArray a=j.getJSONArray("keys");for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);Key k=new Key(o.getLong("time"),o.getInt("mode"),(float)o.getDouble("x"),(float)o.getDouble("y"),(float)o.getDouble("depth"),(float)o.getDouble("seconds"));k.strong=o.optBoolean("strong",true);k.outHandle=(float)o.optDouble("outHandle",0);k.inHandle=(float)o.optDouble("inHandle",1);p.put(k);}return p;}
}
