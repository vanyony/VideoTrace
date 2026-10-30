package com.videotrace.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanvasValidatorTest {
    final ObjectMapper json=new ObjectMapper();
    ObjectNode element(String id,String kind,int x,int y) {
        ObjectNode node=json.createObjectNode().put("id",id).put("kind",kind).put("x",x).put("y",y)
            .put("width",120).put("height",100).put("color","#64dfbf").put("fill","#172641")
            .put("text","").put("fontSize",28).put("icon","agent").put("opacity",1).put("z",1);
        node.putArray("keyframes"); return node;
    }
    ObjectNode canvas() {
        ObjectNode canvas=json.createObjectNode().put("background","#0b1428");
        var nodes=canvas.putArray("elements");
        nodes.add(element("video","rectangle",180,500)); nodes.add(element("agent","icon",500,700));
        nodes.add(element("link","arrow",360,640).put("fromId","video").put("toId","agent"));
        return canvas;
    }
    @Test void rejectsUnknownFieldsAtEveryCanvasDepth() {
        var original=canvas().put("layout","full");
        original.putArray("camera").addObject().put("timeMs",0).put("x",360).put("y",640).put("zoom",1).put("easing","linear");
        var node=(ObjectNode)original.path("elements").get(0);
        node.putArray("keyframes").addObject().put("timeMs",0).put("opacity",1).put("easing","linear");
        var curve=node.putObject("curve").put("startMs",0).put("endMs",2000).put("loop",false);
        var points=curve.putArray("points");
        for(int i=0;i<4;i++)points.addObject().put("x",180+i*20).put("y",500+i*20);
        assertDoesNotThrow(()->CanvasValidator.validate(original,6000));
        for(String pointer:new String[]{"","/camera/0","/elements/0","/elements/0/keyframes/0","/elements/0/curve","/elements/0/curve/points/0"}) {
            var candidate=original.deepCopy(); ((ObjectNode)candidate.at(pointer)).put("code","malicious()");
            assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(candidate,6000),pointer);
        }
    }
    @Test void validatesReferencesAndRejectsUnknownTargetsAndKinds() {
        var canvas=canvas(); assertDoesNotThrow(()->CanvasValidator.validate(canvas,6000));
        var link=(ObjectNode)canvas.path("elements").get(2);
        link.put("toId","missing"); assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
        link.put("toId","link"); assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
        link.put("toId","agent").put("kind","execute-code"); assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
    }
    @Test void checksEveryKeyframeBoundsDurationAndUniqueness() {
        var canvas=canvas(); var node=(ObjectNode)canvas.path("elements").get(0);
        var keys=node.putArray("keyframes");
        keys.addObject().put("timeMs",0).put("x",180).put("opacity",0).put("easing","linear");
        var end=keys.addObject().put("timeMs",2000).put("x",300).put("opacity",1).put("easing","easeOut");
        assertDoesNotThrow(()->CanvasValidator.validate(canvas,6000));
        end.put("x",10); assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
        end.put("x",300).put("timeMs",7000); assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
        end.put("timeMs",0); assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
        end.put("timeMs",2000); node.put("id","agent"); assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
    }
    @Test void validatesSpritesCurvesAndRotationBounds() {
        var canvas=canvas();
        var sprite=(ObjectNode)canvas.path("elements").get(0);
        sprite.put("kind","sprite").put("sprite","droplet").put("width",40).put("height",40);
        var curve=sprite.putObject("curve").put("startMs",0).put("endMs",2000).put("loop",false);
        var points=curve.putArray("points");
        for(int i=0;i<4;i++) points.addObject().put("x",180+i*60).put("y",500+i*50);
        canvas.put("continuePrevious",true);
        assertDoesNotThrow(()->CanvasValidator.validate(canvas,6000));
        ((ObjectNode)points.get(1)).put("x",42);
        assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
        ((ObjectNode)points.get(1)).put("x",240);
        curve.put("endMs",7000);
        assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
        curve.put("endMs",2000); sprite.put("sprite","unknown");
        assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
        sprite.put("sprite","sun").remove("curve");
        sprite.put("x",65).put("rotation",45);
        assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
    }
    @Test void fullLayoutAllowsCameraAndClippingButBoundsResources() {
        var canvas=canvas().put("layout","full");
        var camera=canvas.putArray("camera");
        camera.addObject().put("timeMs",0).put("x",360).put("y",640).put("zoom",1).put("easing","linear");
        var end=camera.addObject().put("timeMs",2000).put("x",420).put("y",600).put("zoom",1.4).put("easing","easeInOut");
        var node=(ObjectNode)canvas.path("elements").get(0);
        node.put("x",-120).put("kind","sprite").put("sprite","water");
        assertDoesNotThrow(()->CanvasValidator.validate(canvas,6000));
        end.put("timeMs",7000); assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
        end.put("timeMs",2000); node.put("width",1280).put("height",1280).put("scale",4);
        assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
        node.put("width",120).put("height",100).put("scale",1); canvas.put("layout","framed");
        assertThrows(IllegalArgumentException.class,()->CanvasValidator.validate(canvas,6000));
    }
}
