package com.videotrace.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Structural validation of the declarative canvas at the callback boundary. */
final class CanvasValidator {
    private CanvasValidator() {}
    static void validate(JsonNode canvas,long duration) {
        if(!canvas.isObject()) fail();
        color(canvas,"background");
        String layout=canvas.path("layout").asText("framed");
        if(!Set.of("framed","full").contains(layout)) fail();
        boolean full=layout.equals("full");
        double cameraZoom=1;
        if(canvas.has("camera")) {
            JsonNode camera=canvas.path("camera");
            if(!camera.isArray() || camera.size()>8 || (!full && !camera.isEmpty())) fail();
            long last=-1;
            for(JsonNode key:camera) {
                integer(key,"timeMs",0,duration);
                long time=key.path("timeMs").asLong(); if(time<=last) fail(); last=time;
                number(key,"x",-720,1440); number(key,"y",-1280,2560);
                cameraZoom=Math.max(cameraZoom,number(key,"zoom",.5,3));
                if(!Set.of("linear","easeInOut","easeOut").contains(key.path("easing").asText())) fail();
            }
        }
        if(canvas.has("continuePrevious") && !canvas.path("continuePrevious").isBoolean()) fail();
        JsonNode elements=canvas.path("elements");
        if(!elements.isArray() || elements.isEmpty() || elements.size()>(full?40:20)) fail();
        Map<String,JsonNode> nodes=new HashMap<>();
        for(JsonNode e:elements) {
            String id=e.path("id").asText();
            if(!id.matches("[A-Za-z0-9_-]{1,32}") || nodes.put(id,e)!=null) fail();
        }
        for(JsonNode e:elements) {
            String kind=e.path("kind").asText();
            String space=e.path("space").asText("world");
            if(!Set.of("world","screen").contains(space)) fail();
            if(!Set.of("circle","rectangle","text","icon","line","arrow","sprite","path").contains(kind)) fail();
            boolean connector=Set.of("line","arrow").contains(kind), path=kind.equals("path"), stroke=connector||path;
            double x=number(e,"x",full?-720:0,full?1440:720),y=number(e,"y",full?-1280:0,full?2560:1280),scale=present(e,"scale")?number(e,"scale",.25,full?4:2):1;
            double rotation=present(e,"rotation")?number(e,"rotation",-360,360):0;
            boolean rotated=rotation!=0, pulse=e.path("pulse").asBoolean(false);
            if(e.has("pulse") && !e.path("pulse").isBoolean()) fail();
            if(stroke && (scale!=1 || rotation!=0 || pulse)) fail();
            if(e.has("sprite") && !Set.of("sun","earth","cloud","droplet","factory","truck","water","ripple").contains(e.path("sprite").asText())) fail();
            double width=number(e,"width",8,full?1280:640),height=number(e,"height",8,full?1280:600);
            number(e,"opacity",0,1); integer(e,"fontSize",16,full?160:64); integer(e,"z",0,20);
            color(e,"color"); color(e,"fill");
            if(!e.path("text").isTextual() || e.path("text").asText().length()>(full?120:60)) fail();
            if(kind.equals("text") && e.path("text").asText().isBlank()) fail();
            if(Set.of("icon","line","arrow","sprite","path").contains(kind) && !e.path("text").asText().isEmpty()) fail();
            if(!Set.of("video","audio","text","agent","cards","phone","server","database","person","file").contains(e.path("icon").asText())) fail();
            if(connector) {
                String from=e.path("fromId").asText(),to=e.path("toId").asText();
                if(from.equals(to) || !nodes.containsKey(from) || !nodes.containsKey(to)) fail();
                if(!nodes.get(from).path("space").asText("world").equals(space) || !nodes.get(to).path("space").asText("world").equals(space)) fail();
                if(Set.of("line","arrow","path").contains(nodes.get(from).path("kind").asText()) || Set.of("line","arrow","path").contains(nodes.get(to).path("kind").asText())) fail();
            } else if(present(e,"fromId") || present(e,"toId")) fail();
            JsonNode curve=e.path("curve");
            if(path && !curve.isObject()) fail();
            if(present(e,"curve")) {
                if(connector || !curve.isObject() || !curve.path("points").isArray() || curve.path("points").size()!=4) fail();
                integer(curve,"startMs",0,duration); integer(curve,"endMs",1,duration);
                if(curve.path("endMs").asLong()<=curve.path("startMs").asLong() || !curve.path("loop").isBoolean()) fail();
                for(JsonNode point:curve.path("points")) { number(point,"x",full?-720:40,full?1440:680); number(point,"y",full?-1280:360,full?2560:980); }
            }
            JsonNode frames=e.path("keyframes");
            if(!frames.isArray() || frames.size()>(full?12:4)) fail();
            long previous=-1;
            // A t=0 keyframe replaces the base state, matching Python interpolation.
            double maxScale=scale;
            if(!stroke && !present(e,"curve") && (frames.isEmpty() || frames.get(0).path("timeMs").asLong()!=0)) bounds(x,y,width,height,scale,full);
            for(JsonNode frame:frames) {
                integer(frame,"timeMs",0,duration);
                long time=frame.path("timeMs").asLong(); if(time<=previous) fail(); previous=time;
                if(!Set.of("linear","easeInOut","easeOut").contains(frame.path("easing").asText())) fail();
                if(present(frame,"opacity")) number(frame,"opacity",0,1);
                if(stroke && (present(frame,"x") || present(frame,"y") || present(frame,"scale") || present(frame,"rotation"))) fail();
                if(present(e,"curve") && (present(frame,"x") || present(frame,"y"))) fail();
                if(present(frame,"x")) x=number(frame,"x",full?-720:0,full?1440:720);
                if(present(frame,"y")) y=number(frame,"y",full?-1280:0,full?2560:1280);
                if(present(frame,"scale")) scale=number(frame,"scale",.25,full?4:2);
                maxScale=Math.max(maxScale,scale);
                if(present(frame,"rotation")) rotated|=number(frame,"rotation",-360,360)!=0;
                if(!stroke && !present(e,"curve")) bounds(x,y,width,height,scale,full);
            }
            if(!stroke) {
                double size=rotated?Math.hypot(width,height):0;
                double w=rotated?size:width,h=rotated?size:height;
                double limit=maxScale*(pulse?1.06:1);
                if(w*h*Math.pow(limit*(space.equals("world")?cameraZoom:1),2)>8_000_000) fail();
                if(present(e,"curve")) {
                    for(JsonNode point:curve.path("points")) bounds(point.path("x").asDouble(),point.path("y").asDouble(),w,h,limit,full);
                } else {
                    x=e.path("x").asDouble();y=e.path("y").asDouble();
                    if(frames.isEmpty() || frames.get(0).path("timeMs").asLong()!=0) bounds(x,y,w,h,limit,full);
                    for(JsonNode frame:frames) {
                        if(present(frame,"x")) x=frame.path("x").asDouble();
                        if(present(frame,"y")) y=frame.path("y").asDouble();
                        bounds(x,y,w,h,limit,full);
                    }
                }
            }
        }
    }
    static boolean present(JsonNode node,String key) { return node.hasNonNull(key); }
    static double number(JsonNode node,String key,double min,double max) {
        JsonNode value=node.path(key); double result=value.asDouble(Double.NaN);
        if(!value.isNumber() || !Double.isFinite(result) || result<min || result>max) fail();
        return result;
    }
    static void integer(JsonNode node,String key,long min,long max) {
        JsonNode value=node.path(key);
        if(!value.isIntegralNumber() || value.asLong()<min || value.asLong()>max) fail();
    }
    static void color(JsonNode node,String key) {
        if(!node.path(key).isTextual() || !node.path(key).asText().matches("#[0-9a-fA-F]{6}")) fail();
    }
    static void bounds(double x,double y,double w,double h,double scale,boolean full) {
        if(full) return;
        if(x-w*scale/2<40 || x+w*scale/2>680 || y-h*scale/2<360 || y+h*scale/2>980) fail();
    }
    static void fail() { throw new IllegalArgumentException("自由画布元素、引用、关键帧或边界无效"); }
}
