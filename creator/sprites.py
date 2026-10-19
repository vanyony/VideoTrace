"""Reusable vector-style assets. Fixed local drawing functions, never model code."""
import math
from functools import lru_cache

from PIL import Image, ImageDraw


@lru_cache(maxsize=48)
def sprite_image(name, color, fill):
    image = Image.new("RGBA", (320, 320))
    d = ImageDraw.Draw(image)
    if name == "sun":
        for i in range(12):
            a = i * math.tau / 12
            d.line((160+112*math.cos(a),160+112*math.sin(a),160+143*math.cos(a),160+143*math.sin(a)),fill=color,width=7)
        d.ellipse((66,66,254,254),fill=color)
        d.ellipse((88,85,203,200),fill=fill)
    elif name == "earth":
        d.ellipse((16,16,304,304),fill=fill,outline=color,width=3)
        d.polygon([(55,90),(92,55),(139,66),(153,100),(121,121),(140,143),(100,159),(70,138)],fill=color)
        d.polygon([(133,164),(166,172),(179,210),(153,255),(142,238),(141,202),(119,180)],fill=color)
        d.polygon([(190,70),(241,77),(274,111),(242,129),(221,150),(191,137),(172,106)],fill=color)
        d.polygon([(189,145),(222,160),(231,196),(208,235),(186,202)],fill=color)
        d.ellipse((233,225,267,246),fill=color)
        d.arc((50,95,190,126),190,340,fill="#d8fff5",width=4)
    elif name == "cloud":
        for box in ((30,133,148,247),(83,72,227,225),(177,119,292,246)):
            d.ellipse(box,fill=fill)
        d.rounded_rectangle((42,162,277,248),34,fill=fill)
        d.arc((85,73,226,216),190,295,fill=color,width=5)
        d.line((70,246,249,246),fill=color,width=4)
    elif name == "droplet":
        d.polygon([(160,27),(71,161),(249,161)],fill=fill)
        d.ellipse((64,107,256,299),fill=fill)
        d.arc((91,144,194,266),90,175,fill=color,width=10)
    elif name == "factory":
        d.rounded_rectangle((34,139,288,279),14,fill=fill,outline=color,width=5)
        d.polygon([(34,139),(107,93),(107,139),(178,94),(178,139)],fill=fill,outline=color,width=4)
        d.rectangle((221,48,255,142),fill=fill,outline=color,width=5)
        for x in (62,126,190):
            d.rounded_rectangle((x,181,x+35,224),5,fill=color)
    elif name == "truck":
        d.rounded_rectangle((22,92,197,238),12,fill=fill,outline=color,width=5)
        d.polygon([(197,130),(254,130),(295,184),(295,238),(197,238)],fill=fill,outline=color,width=5)
        d.polygon([(210,145),(246,145),(271,181),(210,181)],fill=color)
        for x in (85,245):
            d.ellipse((x-27,213,x+27,267),fill="#101d30",outline=color,width=5)
            d.ellipse((x-9,231,x+9,249),fill=color)
    elif name == "water":
        edge = [(x, 30+10*math.sin(x/55)) for x in range(0,321,4)]
        d.polygon([*edge,(320,320),(0,320)],fill=fill)
        d.line(edge,fill=color,width=5)
        for row in range(4):
            y=90+row*60
            d.arc((20+(row%2)*30,y,140+(row%2)*30,y+17),0,175,fill=color,width=2)
            d.arc((195,y+20,300,y+34),0,175,fill=color,width=2)
    elif name == "ripple":
        for inset in (20,65,110):
            d.ellipse((inset,inset/2+75,320-inset,245-inset/2),outline=color,width=4)
    else:
        raise ValueError("unknown local sprite")
    return image
