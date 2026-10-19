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
        JsonNode elements=canvas.path("elements");
        if(!elements.isArray() || elements.isEmpty() || elements.size()>20) fail();
        Map<String,JsonNode> nodes=new HashMap<>();
        for(JsonNode e:elements) {
            String id=e.path("id").asText();
            if(!id.matches("[A-Za-z0-9_-]{1,32}") || nodes.put(id,e)!=null) fail();
        }
        for(JsonNode e:elements) {
            String kind=e.path("kind").asText();
            if(!Set.of("circle","rectangle","text","icon","line","arrow").contains(kind)) fail();
            boolean connector=Set.of("line","arrow").contains(kind);
            double x=number(e,"x",0,720),y=number(e,"y",0,1280),scale=1;
            double width=number(e,"width",8,640),height=number(e,"height",8,600);
            number(e,"opacity",0,1); integer(e,"fontSize",16,64); integer(e,"z",0,20);
            color(e,"color"); color(e,"fill");
            if(!e.path("text").isTextual() || e.path("text").asText().length()>60) fail();
            if(kind.equals("text") && e.path("text").asText().isBlank()) fail();
            if(Set.of("icon","line","arrow").contains(kind) && !e.path("text").asText().isEmpty()) fail();
            if(!Set.of("video","audio","text","agent","cards","phone","server","database","person","file").contains(e.path("icon").asText())) fail();
            if(connector) {
                String from=e.path("fromId").asText(),to=e.path("toId").asText();
                if(from.equals(to) || !nodes.containsKey(from) || !nodes.containsKey(to)) fail();
                if(Set.of("line","arrow").contains(nodes.get(from).path("kind").asText()) || Set.of("line","arrow").contains(nodes.get(to).path("kind").asText())) fail();
            } else if(present(e,"fromId") || present(e,"toId")) fail();
            JsonNode frames=e.path("keyframes");
            if(!frames.isArray() || frames.size()>4) fail();
            long previous=-1;
            // A t=0 keyframe replaces the base state, matching Python interpolation.
            if(!connector && (frames.isEmpty() || frames.get(0).path("timeMs").asLong()!=0)) bounds(x,y,width,height,scale);
            for(JsonNode frame:frames) {
                integer(frame,"timeMs",0,duration);
                long time=frame.path("timeMs").asLong(); if(time<=previous) fail(); previous=time;
                if(!Set.of("linear","easeInOut","easeOut").contains(frame.path("easing").asText())) fail();
                if(present(frame,"opacity")) number(frame,"opacity",0,1);
                if(connector && (present(frame,"x") || present(frame,"y") || present(frame,"scale"))) fail();
                if(present(frame,"x")) x=number(frame,"x",0,720);
                if(present(frame,"y")) y=number(frame,"y",0,1280);
                if(present(frame,"scale")) scale=number(frame,"scale",.25,2);
                if(!connector) bounds(x,y,width,height,scale);
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
    static void bounds(double x,double y,double w,double h,double scale) {
        if(x-w*scale/2<40 || x+w*scale/2>680 || y-h*scale/2<360 || y+h*scale/2>980) fail();
    }
    static void fail() { throw new IllegalArgumentException("自由画布元素、引用、关键帧或边界无效"); }
}
