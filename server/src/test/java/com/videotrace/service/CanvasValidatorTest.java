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
}
