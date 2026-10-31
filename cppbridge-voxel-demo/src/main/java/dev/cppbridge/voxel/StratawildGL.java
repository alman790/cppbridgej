package dev.cppbridge.voxel;

import dev.cppbridge.CppBridge;
import dev.cppbridge.memory.NativeFloatArray;
import dev.cppbridge.memory.NativeIntArray;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Minimal original OpenGL renderer. The Java side handles window/input/GPU buffers;
 * CppBridgeJ calls the native C++ world and greedy-mesh builder in large batches.
 * LWJGL is a low-level OpenGL/GLFW binding, not a game engine or asset library.
 */
public final class StratawildGL implements AutoCloseable {
    private static final int CHUNK_LIMIT = 16 * 16;
    private static final int GPU_UPLOADS_PER_FRAME = 2;
    private static final int GPU_BYTES_PER_FRAME = 1024 * 1024;
    private final VoxelNative api;
    private final Map<Long, GpuChunk> meshes = new HashMap<>();
    private long window;
    private int program;
    private int vao;
    private int locCamera, locAngles, locAspect, locChunk;
    private int locLodFade,locLodRole;
    private final int[] inventory = new int[10];
    private static final float LOD_START = 80f, LOD_END = 103f;
    private float yaw=0, pitch=0;
    private double oldMouseX,oldMouseY;
    private boolean firstMouse=true,captured=true;
    private int selected=1;
    private int action;
    private int width=1280,height=720;
    private long frames,lastTitleNanos;
    private boolean showStreamingStats=false;
    private final FrameTimeStats frameStats=new FrameTimeStats(240);

    private static final class GpuChunk {
        final int buffer;
        final int farBuffer;
        final int waterBuffer;
        int revision;
        int quads;
        int farQuads;
        int waterQuads;
        int quadCapacityBytes;
        int waterCapacityBytes;
        GpuChunk(int buffer,int farBuffer,int waterBuffer) {
            this.buffer=buffer;this.farBuffer=farBuffer;this.waterBuffer=waterBuffer;
        }
    }

    public static void main(String[] args) {
        VoxelNative api=CppBridge.load(VoxelNative.class);
        long seed=args.length==0?0x5B8A12E6L:Long.parseLong(args[0]);
        api.init(seed);
        try(StratawildGL game=new StratawildGL(api)) {game.run();}
    }

    private StratawildGL(VoxelNative api) {this.api=api;}

    private void openWindow() {
        GLFWErrorCallback.createPrint(System.err).set();
        if(!glfwInit()) throw new IllegalStateException("GLFW initialization failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT,GLFW_TRUE); // Required on macOS.
        glfwWindowHint(GLFW_SAMPLES,4);
        window=glfwCreateWindow(width,height,"Stratawild — GPU voxel sandbox",0,0);
        if(window==0) throw new IllegalStateException("Cannot create OpenGL 3.3 window");
        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
        glEnable(GL_CULL_FACE);
        // Projection uses a +Z-forward camera convention; front-facing world
        // surfaces therefore have clockwise screen-space winding.
        glFrontFace(GL_CW);
        glCullFace(GL_BACK);
        glEnable(GL_MULTISAMPLE);
        glClearColor(.647f,.761f,.824f,1f);
        program=createProgram(VERTEX_SHADER,FRAGMENT_SHADER);
        locCamera=glGetUniformLocation(program,"uCamera");
        locAngles=glGetUniformLocation(program,"uAngles");
        locAspect=glGetUniformLocation(program,"uAspect");
        locChunk=glGetUniformLocation(program,"uChunk");
        locLodFade=glGetUniformLocation(program,"uLodFade");
        locLodRole=glGetUniformLocation(program,"uLodRole");
        vao=glGenVertexArrays();
        glBindVertexArray(vao);
        glEnableVertexAttribArray(0);
        glVertexAttribDivisor(0,1); // One packed 8-byte quad per instance.
        glfwSetInputMode(window,GLFW_CURSOR,GLFW_CURSOR_DISABLED);
        glfwSetCursorPosCallback(window,(handle,x,y)-> {
            if(!captured) return;
            if(firstMouse) {oldMouseX=x;oldMouseY=y;firstMouse=false;return;}
            yaw+=(float)((x-oldMouseX)*.0024);
            pitch=Math.max(-1.52f,Math.min(1.52f,pitch-(float)((y-oldMouseY)*.0024)));
            oldMouseX=x;oldMouseY=y;
        });
        glfwSetKeyCallback(window,(handle,key,scanCode,act,mods)-> {
            if(act!=GLFW_PRESS) return;
            if(key==GLFW_KEY_ESCAPE) {
                if(captured) setCaptured(false);
                else glfwSetWindowShouldClose(window,true);
            }
            if(key>=GLFW_KEY_1&&key<=GLFW_KEY_8) selected=key-GLFW_KEY_0;
            if(key==GLFW_KEY_F3) showStreamingStats=!showStreamingStats;
            if(key==GLFW_KEY_F5) {
                int result=api.save();
                System.out.println(result==1?"World saved":"Failed to save world");
            }
        });
        glfwSetMouseButtonCallback(window,(handle,button,act,mods)-> {
            if(act!=GLFW_PRESS) return;
            if(!captured) {setCaptured(true);return;}
            if(button==GLFW_MOUSE_BUTTON_LEFT) action=1;
            if(button==GLFW_MOUSE_BUTTON_RIGHT) action=2;
        });
        glfwSetFramebufferSizeCallback(window,(handle,w,h)-> {
            width=Math.max(1,w);height=Math.max(1,h);
            glViewport(0,0,width,height);
        });
        try(MemoryStack stack=MemoryStack.stackPush()) {
            IntBuffer w=stack.mallocInt(1),h=stack.mallocInt(1);
            glfwGetFramebufferSize(window,w,h);
            width=Math.max(1,w.get(0));height=Math.max(1,h.get(0));
            glViewport(0,0,width,height);
        }
    }

    private void setCaptured(boolean enable) {
        captured=enable;
        firstMouse=true;
        glfwSetInputMode(window,GLFW_CURSOR,
                enable?GLFW_CURSOR_DISABLED:GLFW_CURSOR_NORMAL);
    }

    private int keys() {
        if(!captured) return 0;
        int k=0;
        if(down(GLFW_KEY_W)) k|=1;
        if(down(GLFW_KEY_S)) k|=2;
        if(down(GLFW_KEY_A)) k|=4;
        if(down(GLFW_KEY_D)) k|=8;
        if(down(GLFW_KEY_SPACE)) k|=16;
        if(down(GLFW_KEY_LEFT_SHIFT)||down(GLFW_KEY_RIGHT_SHIFT)) k|=32;
        return k;
    }
    private boolean down(int key) {return glfwGetKey(window,key)==GLFW_PRESS;}
    private static long chunkKey(int x,int z) {return ((long)x<<32)|(z&0xffffffffL);}

    private void run() {
        openWindow();
        lastTitleNanos=System.nanoTime();
        long previous=lastTitleNanos;
        // Persistent off-heap buffers: no per-frame native allocations or data copies.
        try(NativeIntArray list=NativeIntArray.allocate(CHUNK_LIMIT*4);
            NativeFloatArray camera=NativeFloatArray.allocate(5);
            NativeIntArray items=NativeIntArray.allocate(10);
            NativeIntArray streamStats=NativeIntArray.allocate(10)) {
            while(!glfwWindowShouldClose(window)) {
                long now=System.nanoTime();
                float dt=Math.min(.05f,Math.max(.001f,(now-previous)*1e-9f));
                previous=now;
                glfwPollEvents();
                api.tick(keys(),yaw,pitch,dt);
                if(action!=0) {api.interact(action,selected);action=0;}
                api.camera(camera);
                synchronizeMeshes(list,camera);
                draw(camera);
                glfwSwapBuffers(window);
                frameStats.addNanos(System.nanoTime()-now);
                if(++frames>0 && now-lastTitleNanos>=1_000_000_000L) {
                    api.inventory(items);
                    for(int i=0;i<10;i++) inventory[i]=items.get(i);
                    String diagnostic=" | F3 stats";
                    if(showStreamingStats) {
                        api.streamStats(streamStats);
                        diagnostic=" | genQ "+streamStats.get(1)
                                +" | meshQ "+streamStats.get(3)
                                +" | pending "+streamStats.get(5)
                                +" | applied "+streamStats.get(6)
                                +" | stale "+streamStats.get(7)
                                +" | canceled "+(streamStats.get(8)+streamStats.get(9));
                    }
                    glfwSetWindowTitle(window,"Stratawild 0.9 | "+frames+" FPS | p95 "
                            +String.format(java.util.Locale.ROOT,"%.1f",frameStats.p95Ms())
                            +" ms | p99 "+String.format(java.util.Locale.ROOT,"%.1f",frameStats.p99Ms())
                            +" ms | "+meshes.size()+" chunks | slot "+selected+": "
                            +inventory[selected]+diagnostic+" | F5 save | Esc");
                    frames=0;lastTitleNanos=now;
                }
            }
        } finally {api.save();}
    }

    private static int nextCapacity(int bytes) {
        int capacity=256;
        while(capacity<bytes && capacity<=Integer.MAX_VALUE/2) capacity*=2;
        if(capacity<bytes) throw new IllegalArgumentException("GPU mesh too large");
        return capacity;
    }

    /** Reuse VBO identities; orphan/reallocate only when a mesh outgrows capacity. */
    private static int uploadDynamic(int buffer,NativeIntArray data,int words,int currentBytes) {
        int bytes=Math.multiplyExact(words,Integer.BYTES);
        glBindBuffer(GL_ARRAY_BUFFER,buffer);
        if(bytes>currentBytes) {
            currentBytes=nextCapacity(bytes);
            glBufferData(GL_ARRAY_BUFFER,(long)currentBytes,GL_DYNAMIC_DRAW);
        }
        if(bytes>0) glBufferSubData(GL_ARRAY_BUFFER,0L,
                data.segment().asSlice(0,bytes).asByteBuffer());
        return currentBytes;
    }

    private void synchronizeMeshes(NativeIntArray list,NativeFloatArray camera) {
        int count=api.meshList(list);
        Set<Long> alive=new HashSet<>(count*2+1);
        ArrayList<MeshUploadBudget.Candidate> candidates=new ArrayList<>();
        for(int i=0;i<count;++i) {
            int cx=list.get(i*4),cz=list.get(i*4+1);
            int revision=list.get(i*4+2),quadCount=list.get(i*4+3);
            long key=chunkKey(cx,cz);
            alive.add(key);
            GpuChunk existing=meshes.get(key);
            if(existing!=null&&existing.revision==revision) continue;
            if(quadCount<0||quadCount>1_000_000) continue;
            candidates.add(new MeshUploadBudget.Candidate(cx,cz,revision,quadCount,
                    existing==null));
        }
        // Bounded GPU uploads: nearby/forward chunks first. Deferred revisions
        // are discovered again on the next frame without extra persistent queues.
        var chosen=MeshUploadBudget.select(candidates,camera.get(0),camera.get(2),
                camera.get(3),GPU_UPLOADS_PER_FRAME,GPU_BYTES_PER_FRAME);
        for(var job:chosen) {
            int cx=job.cx(),cz=job.cz(),revision=job.revision(),quadCount=job.quads();
            long key=chunkKey(cx,cz);
            GpuChunk existing=meshes.get(key);
            // Revision zero means only coarse terrain exists at this point.
            int waterWords=revision==0?0:api.waterWords(cx,cz);
            if(waterWords<0||waterWords>2_000_000||(waterWords&1)!=0) continue;
            try(NativeIntArray quads=NativeIntArray.allocate(Math.max(1,quadCount*2));
                NativeIntArray water=NativeIntArray.allocate(Math.max(1,waterWords));
                NativeIntArray far=existing==null?NativeIntArray.allocate(512):null) {
                int copied=revision==0?0:api.meshCopy(cx,cz,quads);
                int waterCopied=revision==0?0:api.meshCopyWater(cx,cz,water);
                int farCopied=existing==null?api.meshCopyFar(cx,cz,far):existing.farQuads*2;
                if(copied!=quadCount*2 || waterCopied!=waterWords ||
                        farCopied<0 || (farCopied&1)!=0 ||
                        (existing==null && farCopied>far.length())) continue;
                GpuChunk chunk=existing;
                if(chunk==null) {
                    chunk=new GpuChunk(glGenBuffers(),glGenBuffers(),glGenBuffers());
                }
                try {
                    if(existing==null) {
                        glBindBuffer(GL_ARRAY_BUFFER,chunk.farBuffer);
                        glBufferData(GL_ARRAY_BUFFER,
                                far.segment().asSlice(0,(long)farCopied*Integer.BYTES)
                                        .asByteBuffer(),GL_STATIC_DRAW);
                    }
                    chunk.quadCapacityBytes=uploadDynamic(chunk.buffer,quads,copied,
                            chunk.quadCapacityBytes);
                    chunk.waterCapacityBytes=uploadDynamic(chunk.waterBuffer,water,waterCopied,
                            chunk.waterCapacityBytes);
                    chunk.revision=revision;
                    chunk.quads=quadCount;
                    chunk.waterQuads=waterWords/2;
                    if(existing==null) {
                        chunk.farQuads=farCopied/2;
                        meshes.put(key,chunk);
                    }
                } catch(RuntimeException | Error failure) {
                    if(existing==null) {
                        glDeleteBuffers(chunk.buffer);
                        glDeleteBuffers(chunk.farBuffer);
                        glDeleteBuffers(chunk.waterBuffer);
                    }
                    throw failure;
                }
            }
        }
        Iterator<Map.Entry<Long,GpuChunk>> it=meshes.entrySet().iterator();
        while(it.hasNext()) {
            var entry=it.next();
            if(!alive.contains(entry.getKey())) {
                glDeleteBuffers(entry.getValue().buffer);
                glDeleteBuffers(entry.getValue().farBuffer);
                glDeleteBuffers(entry.getValue().waterBuffer);
                it.remove();
            }
        }
    }

    private void submitMesh(int cx,int cz,int buffer,int count,int role,float fade) {
        if(count==0) return;
        glUniform2i(locChunk,cx,cz);
        glUniform1i(locLodRole,role);
        glUniform1f(locLodFade,fade);
        glBindBuffer(GL_ARRAY_BUFFER,buffer);
        glVertexAttribIPointer(0,2,GL_UNSIGNED_INT,2*Integer.BYTES,0L);
        glDrawArraysInstanced(GL_TRIANGLES,0,6,count);
    }

    private void draw(NativeFloatArray cam) {
        float ex=cam.get(0),ey=cam.get(1),ez=cam.get(2);
        float ay=cam.get(3),ap=cam.get(4);
        float cosPitch=(float)Math.cos(ap);
        float fx=(float)Math.sin(ay)*cosPitch;
        float fy=(float)Math.sin(ap);
        float fz=(float)Math.cos(ay)*cosPitch;
        float rx=(float)Math.cos(ay),rz=-(float)Math.sin(ay);
        float aspect=(float)width/height;
        glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        glUseProgram(program);
        glUniform3f(locCamera,ex,ey,ez);
        glUniform2f(locAngles,ay,ap);
        glUniform1f(locAspect,aspect);
        glBindVertexArray(vao);
        record Visible(int cx,int cz,GpuChunk chunk,float dist) {}
        ArrayList<Visible> visible=new ArrayList<>(meshes.size());
        for(var entry:meshes.entrySet()) {
            int cx=(int)(entry.getKey()>>32),cz=(int)(long)entry.getKey();
            float dx=cx*16f+8f-ex,dy=40f-ey,dz=cz*16f+8f-ez;
            float distSquared=dx*dx+dy*dy+dz*dz;
            if(distSquared>150f*150f) continue;
            float forward=fx*dx+fy*dy+fz*dz;
            float right=rx*dx+rz*dz;
            if(forward< -45f || Math.abs(right)>Math.max(0f,forward)*.71f*aspect+33f) continue;
            visible.add(new Visible(cx,cz,entry.getValue(),(float)Math.sqrt(distSquared)));
        }
        // Stable ordered-dither transition. Only one representation survives at
        // each screen pixel; the distant mesh is not fully transparent-blended.
        for(Visible v:visible) {
            GpuChunk c=v.chunk();
            float t=Math.max(0f,Math.min(1f,(v.dist()-LOD_START)/(LOD_END-LOD_START)));
            // Draw the coarse impostor immediately if background meshing has
            // not delivered detailed geometry yet. Afterward use the existing
            // screen-door transition rather than rendering an empty chunk.
            if(c.revision==0 && c.farQuads>0) {
                submitMesh(v.cx(),v.cz(),c.farBuffer,c.farQuads,1,1f);
            } else {
                if(c.farQuads==0 || t<1f)
                    submitMesh(v.cx(),v.cz(),c.buffer,c.quads,0,t);
                if(c.farQuads>0 && t>0f)
                    submitMesh(v.cx(),v.cz(),c.farBuffer,c.farQuads,1,t);
            }
        }
        // Separate transparent fluid pass, back-to-front by chunk. Geometry
        // inside one chunk is not individually sorted (known limitation).
        visible.sort(Comparator.comparingDouble(Visible::dist).reversed());
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        for(Visible v:visible) {
            if(v.dist()>LOD_END) continue;
            GpuChunk c=v.chunk();
            float t=Math.max(0f,Math.min(1f,(v.dist()-LOD_START)/(LOD_END-LOD_START)));
            submitMesh(v.cx(),v.cz(),c.waterBuffer,c.waterQuads,0,t);
        }
        glDepthMask(true);
        glDisable(GL_BLEND);
        // Center reticle and native-inventory hotbar rendered without textures.
        glEnable(GL_SCISSOR_TEST);
        glScissor(Math.max(0,width/2-9),Math.max(0,height/2-1),18,2);
        glClearColor(.98f,.94f,.79f,1f);glClear(GL_COLOR_BUFFER_BIT);
        glScissor(Math.max(0,width/2-1),Math.max(0,height/2-9),2,18);
        glClear(GL_COLOR_BUFFER_BIT);
        int[] palette={0,0x619F69,0x7B5D4A,0x6F7B87,0xCDB47E,
                       0x8D694C,0x57976F,0x8996CB,0xFFD678};
        int slot=Math.max(20,Math.min(39,width/24)), gap=7;
        int left=(width-(8*slot+7*gap))/2;
        for(int i=1;i<=8;i++) {
            int x=left+(i-1)*(slot+gap),y=15;
            glScissor(Math.max(0,x-3),y-3,slot+6,slot+6);
            if(i==selected) glClearColor(.98f,.87f,.53f,1f);
            else glClearColor(.13f,.18f,.20f,1f);
            glClear(GL_COLOR_BUFFER_BIT);
            int rgb=palette[i];
            float multiplier=inventory[i]>0?1f:.22f;
            glClearColor(((rgb>>16)&255)/255f*multiplier,
                         ((rgb>>8)&255)/255f*multiplier,(rgb&255)/255f*multiplier,1f);
            glScissor(x+4,y+4,slot-8,slot-8);
            glClear(GL_COLOR_BUFFER_BIT);
        }
        glDisable(GL_SCISSOR_TEST);
        glClearColor(.647f,.761f,.824f,1f);
    }

    private static int createProgram(String vertex,String fragment) {
        int vs=compile(GL_VERTEX_SHADER,vertex),fs=compile(GL_FRAGMENT_SHADER,fragment);
        int p=glCreateProgram();
        glAttachShader(p,vs);glAttachShader(p,fs);
        glLinkProgram(p);
        glDeleteShader(vs);glDeleteShader(fs);
        if(glGetProgrami(p,GL_LINK_STATUS)==GL_FALSE)
            throw new IllegalStateException("Shader link failed: "+glGetProgramInfoLog(p));
        return p;
    }
    private static int compile(int kind,String source) {
        int s=glCreateShader(kind);
        glShaderSource(s,source);glCompileShader(s);
        if(glGetShaderi(s,GL_COMPILE_STATUS)==GL_FALSE)
            throw new IllegalStateException("Shader compilation failed: "+glGetShaderInfoLog(s));
        return s;
    }

    @Override public void close() {
        if(window!=0) {
            meshes.values().forEach(chunk->{
                glDeleteBuffers(chunk.buffer);
                glDeleteBuffers(chunk.farBuffer);
                glDeleteBuffers(chunk.waterBuffer);
            });
            meshes.clear();
            if(vao!=0) glDeleteVertexArrays(vao);
            if(program!=0) glDeleteProgram(program);
            glfwDestroyWindow(window);window=0;
        }
        glfwTerminate();
        var cb=glfwSetErrorCallback(null);
        if(cb!=null) cb.free();
    }

    private static final String VERTEX_SHADER="""
        #version 330 core
        layout(location=0) in uvec2 aQuad;
        uniform vec3 uCamera;
        uniform vec2 uAngles;
        uniform float uAspect;
        uniform ivec2 uChunk;
        flat out int vMaterial;
        flat out int vDirection;
        out float vDistance;
        out float vAO;
        out float vLight;
        void main() {
            uint a=aQuad.x,b=aQuad.y;
            vec3 origin=vec3(float(a&31u),float((a>>5u)&127u),float((a>>12u)&31u));
            int direction=int((a>>17u)&7u);
            vMaterial=int((a>>20u)&15u);vDirection=direction;
            float w=float(b&255u), h=float((b>>8u)&255u);
            // Two CCW triangles; winding is flipped for opposite face normals.
            vec2 corners[6]=vec2[6](vec2(0,0),vec2(1,0),vec2(1,1),
                                     vec2(0,0),vec2(1,1),vec2(0,1));
            vec2 uv=corners[gl_VertexID];
            if(direction==1||direction==3||direction==4) uv.x=1.0-uv.x;
            // Four two-bit ambient-occlusion samples packed into the unused
            // high byte of the dimension word. No extra vertex buffer needed.
            int corner=uv.y>.5?(uv.x>.5?2:3):(uv.x>.5?1:0);
            float occluded=float((b>>(16u+uint(corner)*2u))&3u);
            vAO=1.0-.17*occluded;
            vLight=float((b>>(24u+uint(corner)*2u))&3u)/3.0;
            vec3 local;
            if(direction<2) local=origin+vec3(0,uv.y*h,uv.x*w);
            else if(direction<4) local=origin+vec3(uv.x*w,0,uv.y*h);
            else local=origin+vec3(uv.x*w,uv.y*h,0);
            // Water levels are packed in unused word0 bits 24..27.
            // Top faces and exposed vertical sides show reduced flow height;
            // still-water sources (level 8) retain their full-block geometry.
            if(vMaterial==9) {
                float level=float((a>>24u)&15u);
                float drop=(1.0-clamp(level/8.0,0.0,1.0))*.75;
                if(direction==3 || ((direction!=2) && uv.y>.5)) local.y-=drop;
            }
            vec3 world=local+vec3(float(uChunk.x*16),0,float(uChunk.y*16));
            vec3 delta=world-uCamera;
            float cy=cos(uAngles.x),sy=sin(uAngles.x);
            float cp=cos(uAngles.y),sp=sin(uAngles.y);
            vec3 forward=vec3(sy*cp,sp,cy*cp);
            vec3 right=vec3(cy,0,-sy);
            vec3 up=vec3(-sy*sp,cp,-cy*sp);
            vec3 cameraSpace=vec3(dot(delta,right),dot(delta,up),dot(delta,forward));
            const float nearPlane=.1, farPlane=160.0, tanHalfFov=.700208;
            gl_Position=vec4(cameraSpace.x/(tanHalfFov*uAspect),
                             cameraSpace.y/tanHalfFov,
                             (farPlane+nearPlane)/(farPlane-nearPlane)*cameraSpace.z
                               -2*farPlane*nearPlane/(farPlane-nearPlane),cameraSpace.z);
            vDistance=length(delta);
        }
        """;
    private static final String FRAGMENT_SHADER="""
        #version 330 core
        flat in int vMaterial;
        flat in int vDirection;
        in float vDistance;
        in float vAO;
        in float vLight;
        uniform float uLodFade;
        uniform int uLodRole;
        out vec4 fragColor;
        void main() {
            // 4x4 ordered dither, tied to screen pixels to avoid temporal noise.
            int x=int(mod(gl_FragCoord.x,4.0)), y=int(mod(gl_FragCoord.y,4.0));
            int bayer[16]=int[16](0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5);
            float threshold=(float(bayer[y*4+x])+.5)/16.0;
            if(uLodRole==0 && threshold<uLodFade) discard;
            if(uLodRole==1 && threshold>=uLodFade) discard;
            const vec3 palette[10]=vec3[10](
                vec3(0),vec3(.38,.62,.41),vec3(.48,.36,.29),vec3(.43,.48,.53),
                vec3(.80,.71,.49),vec3(.55,.41,.30),vec3(.34,.59,.43),vec3(.54,.59,.80),
                vec3(1.0,.84,.47),vec3(.20,.51,.72));
            float face=vDirection==3?1.0:(vDirection==2?.48:(vDirection<2?.73:.84));
            float ambient=.20+.80*vLight;
            if(vMaterial==8) ambient=max(ambient,1.1);
            vec3 sky=vec3(.647,.761,.824);
            float fog=smoothstep(50.0,125.0,vDistance);
            vec3 color=mix(palette[clamp(vMaterial,0,9)]*face*vAO*ambient,sky,fog);
            float alpha=vMaterial==9?.61:1.0;
            fragColor=vec4(color,alpha);
        }
        """;
}
