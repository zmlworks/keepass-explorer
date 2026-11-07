from PIL import Image
def blend(d,s):
    sa=s[3]/255; da=d[3]/255; oa=sa+da*(1-sa)
    if oa==0: return (0,0,0,0)
    return tuple(int(round((s[i]*sa+d[i]*da*(1-sa))/oa)) for i in range(3))+(int(round(oa*255)),)
def inside(poly,x,y):
    c=False;n=len(poly)
    for i in range(n):
        x1,y1=poly[i];x2,y2=poly[(i+1)%n]
        if (y1>y)!=(y2>y) and x < (x2-x1)*(y-y1)/(y2-y1)+x1: c=not c
    return c
def make(N):
    s=N/32
    a=[[(0,0,0,0)]*N for _ in range(N)]
    def put(x,y,c):
        if 0<=x<N and 0<=y<N: a[y][x]=blend(a[y][x],c)
    def fillpoly(poly,f,edge=None):
        P=[(px*s,py*s) for px,py in poly]
        m=[[inside(P,x+.5,y+.5) for x in range(N)] for y in range(N)]
        for y in range(N):
            for x in range(N):
                if m[y][x]:
                    e= edge and any(not(0<=x+dx<N and 0<=y+dy<N and m[y+dy][x+dx]) for dx,dy in((1,0),(-1,0),(0,1),(0,-1)))
                    put(x,y,edge if e else f(x,y))
        return m
    OUT=(24,28,40,255)
    # shadow
    fillpoly([(13,8),(31,8),(31,31),(13,31)],lambda x,y:(0,0,40,45))
    # page stack (3 sheets visible at right/bottom)
    for k,(col) in enumerate([(200,204,212,255),(225,228,234,255)]):
        o=2-k
        fillpoly([(11+o,6+o),(29+o,6+o),(29+o,29+o),(11+o,29+o)],lambda x,y,c=col:c,(90,96,110,255))
    def paper(x,y):
        v=y/s
        if v>9 and abs(((v-10)%3.2))<0.55*max(1,1/s): return (120,170,230,255)
        if abs(x/s-14.5)<0.5*max(1,1/s) and v>8: return (230,110,110,255)
        return (255,255,255,255)
    fillpoly([(11,6),(29,6),(29,29),(11,29)],paper,OUT)
    # translucent clear cover lifting to the left, hinged at top
    cov=[(11,7),(29,7),(20,25),(1,22)]
    def cf(x,y):
        u=x/s+y/s*0.55
        if 17<u<19.5 or 21<u<21.8: return (255,255,255,150)
        return (120,180,240,120)
    fillpoly(cov,cf,(30,90,180,235))
    # top binding strip + rings
    fillpoly([(10,4),(30,4),(30,7),(10,7)],lambda x,y:(70,80,100,255) if y/s>6 else (150,160,180,255),OUT)
    step=3 if N>=24 else 4
    for i in range(12,29,step):
        for dy in range(0,4):
            put(int(i*s),int((2+dy)*s), (40,44,56,255) if dy in(0,3) else (210,214,224,255))
            put(int(i*s)+1,int((2+dy)*s),(40,44,56,255))
    return a
for N in (32,16):
    a=make(N); im=Image.new('RGBA',(N,N))
    for y in range(N):
        for x in range(N): im.putpixel((x,y),a[y][x])
    im.save(f'memo{N}.png')
pv=Image.new('RGBA',(330,140),(255,255,255,255))
for i,(f,z) in enumerate([('memo32.png',4),('memo16.png',4),('memo32.png',2),('memo16.png',2)]):
    m=Image.open(f); m=m.resize((m.width*z,m.height*z),Image.NEAREST); pv.paste(m,([0,135,210,280][i],4),m)
pv.save('pv.png')
