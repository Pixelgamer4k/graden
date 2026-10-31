// SPDX-License-Identifier: GPL-3.0-only
package com.graden.editor.cinema;
import org.json.*;import java.lang.reflect.Field;
public final class Settings {
 public float focus=.5f,strength=.45f,band=.045f,rotation=0,highlight=0,rim=0,breathing=0;
 public boolean portrait=true,showDepth=false,original=false,hevc=true,mute=false;
 public int longEdge=3840,analysisEdge=1280,bitrateMbps=40,shape=0,samples=96;
 public Settings copy(){Settings s=new Settings();try{for(Field f:getClass().getFields())f.set(s,f.get(this));}catch(Exception e){throw new IllegalStateException(e);}return s;}
 public String json(){try{JSONObject j=new JSONObject();for(Field f:getClass().getFields())j.put(f.getName(),f.get(this));return j.toString();}catch(Exception e){throw new IllegalStateException(e);}}
 public static Settings parse(String data){Settings s=new Settings();try{JSONObject j=new JSONObject(data);for(Field f:Settings.class.getFields())if(j.has(f.getName())){if(f.getType()==float.class)f.setFloat(s,(float)j.getDouble(f.getName()));else if(f.getType()==int.class)f.setInt(s,j.getInt(f.getName()));else f.setBoolean(s,j.getBoolean(f.getName()));}}catch(Exception ignored){}return s;}
}
