package dev.cppbridge.voxel;

import dev.cppbridge.CppBridge;
import dev.cppbridge.memory.NativeIntArray;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Original no-asset voxel sandbox. Java owns UI/input while C++ owns the world, collision and
 * whole-frame ray traversal. All native calls happen on the game thread.
 */
public final class Stratawild extends JPanel
        implements KeyListener, MouseListener, MouseMotionListener, FocusListener {
    private static final int[][] RESOLUTIONS = {{384,216}, {512,288}, {640,360}};
    private static final String[] MATERIALS = {
        "", "Мох", "Глина", "Сланец", "Песок", "Древесина", "Листва", "Кристалл", "Светящийся камень"
    };
    private static final Color[] SWATCHES = {
        Color.BLACK, new Color(97,159,105), new Color(123,93,74), new Color(111,123,135),
        new Color(205,180,126), new Color(141,105,76), new Color(87,151,111),
        new Color(137,150,203), new Color(255,214,120)
    };

    private final VoxelNative api;
    private final Object paintLock = new Object();
    private final AtomicInteger keys = new AtomicInteger();
    private final AtomicInteger action = new AtomicInteger();
    private final AtomicBoolean saveRequest = new AtomicBoolean();
    private final AtomicBoolean resolutionRequest = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile float yaw, pitch;
    private volatile int selected = 1, resolutionIndex = 1, fps, chunkCount;
    private volatile int[] inventory = new int[10];
    private volatile String notice = "ЛКМ — начать играть";
    private volatile long noticeDeadline = Long.MAX_VALUE;
    private volatile boolean captured;
    private BufferedImage framebuffer;
    private JFrame window;
    private Robot robot;
    private Cursor emptyCursor;
    private int lastMouseX=-1, lastMouseY=-1;

    private Stratawild(VoxelNative api) {
        this.api=api;
        setFocusable(true);
        setPreferredSize(new Dimension(1152,648));
        setBackground(Color.BLACK);
        addKeyListener(this);
        addMouseListener(this);
        addMouseMotionListener(this);
        addFocusListener(this);
        try {
            robot=new Robot();
            BufferedImage blank=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);
            emptyCursor=Toolkit.getDefaultToolkit().createCustomCursor(blank,new Point(0,0),"hidden");
        } catch(Exception ignored) {
            robot=null; // Fallback: drag/move mouse inside window without cursor recentering.
        }
    }

    public static void main(String[] args) throws Exception {
        if(GraphicsEnvironment.isHeadless()) {
            System.err.println("Stratawild requires a graphical desktop.");
            return;
        }
        VoxelNative nativeApi=CppBridge.load(VoxelNative.class);
        long seed=0x5B8A12E6L;
        if(args.length>0) seed=Long.parseLong(args[0]);
        nativeApi.init(seed);
        Stratawild[] created=new Stratawild[1];
        SwingUtilities.invokeAndWait(()-> {
            Stratawild panel=new Stratawild(nativeApi);
            JFrame frame=new JFrame("Stratawild — original voxel sandbox");
            panel.window=frame;
            frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {panel.running.set(false);}
            });
            frame.setContentPane(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            panel.requestFocusInWindow();
            created[0]=panel;
        });
        created[0].loop();
    }

    private void loop() {
        long previous=System.nanoTime(), fpsTimer=previous;
        int frames=0;
        try {
            while(running.get()) {
                long start=System.nanoTime();
                float dt=Math.min(0.05f,Math.max(0.001f,(start-previous)*1e-9f));
                previous=start;
                if(resolutionRequest.getAndSet(false)) resolutionIndex=(resolutionIndex+1)%RESOLUTIONS.length;
                int[] size=RESOLUTIONS[resolutionIndex];
                try(NativeIntArray pixels=NativeIntArray.allocate(size[0]*size[1]);
                    NativeIntArray items=NativeIntArray.allocate(10)) {
                    // Reuse the native arena if resolution stays unchanged.
                    while(running.get() && !resolutionRequest.get()) {
                        long frameStart=System.nanoTime();
                        float frameDt=Math.min(0.05f,Math.max(0.001f,(frameStart-previous)*1e-9f));
                        previous=frameStart;
                        api.tick(captured?keys.get():0,yaw,pitch,frameDt);
                        int pending=action.getAndSet(0);
                        if(pending!=0 && captured) api.interact(pending,selected);
                        if(saveRequest.getAndSet(false)) {
                            notice=api.save()==1?"Мир сохранён":"Ошибка сохранения";
                            noticeDeadline=System.currentTimeMillis()+2000;
                        }
                        api.render(pixels,size[0],size[1]);
                        synchronized(paintLock) {
                            if(framebuffer==null || framebuffer.getWidth()!=size[0]
                                    || framebuffer.getHeight()!=size[1]) {
                                framebuffer=new BufferedImage(size[0],size[1],BufferedImage.TYPE_INT_ARGB);
                            }
                            int[] target=((DataBufferInt)framebuffer.getRaster().getDataBuffer()).getData();
                            pixels.copyTo(target); // One contiguous native-to-heap copy per frame.
                        }
                        repaint();
                        ++frames;
                        if(frameStart-fpsTimer>=1_000_000_000L) {
                            fps=frames;frames=0;fpsTimer=frameStart;
                            chunkCount=api.loadedChunks();
                            api.inventory(items);
                            inventory=items.toArray();
                        }
                        long remaining=16_666_667L-(System.nanoTime()-frameStart);
                        if(remaining>0) {
                            try {Thread.sleep(remaining/1_000_000L,(int)(remaining%1_000_000L));}
                            catch(InterruptedException e) {Thread.currentThread().interrupt();running.set(false);}
                        }
                    }
                }
            }
        } finally {
            api.save();
            SwingUtilities.invokeLater(()-> {releaseMouse();window.dispose();});
        }
    }

    private void captureMouse() {
        captured=true;
        lastMouseX=-1;lastMouseY=-1;
        if(emptyCursor!=null) setCursor(emptyCursor);
        requestFocusInWindow();
        centerMouse();
    }
    private void releaseMouse() {
        captured=false;
        lastMouseX=-1;lastMouseY=-1;
        setCursor(Cursor.getDefaultCursor());
        keys.set(0);
    }
    private void centerMouse() {
        if(robot==null || !isShowing()) return;
        try {
            Point p=getLocationOnScreen();
            robot.mouseMove(p.x+getWidth()/2,p.y+getHeight()/2);
        } catch(IllegalStateException ignored) { }
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g=(Graphics2D)graphics.create();
        try {
            synchronized(paintLock) {
                if(framebuffer!=null) {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    int drawW=getWidth(),drawH=getHeight();
                    float screenRatio=(float)getWidth()/Math.max(1,getHeight());
                    float imageRatio=(float)framebuffer.getWidth()/framebuffer.getHeight();
                    if(screenRatio>imageRatio) drawW=Math.round(drawH*imageRatio);
                    else drawH=Math.round(drawW/imageRatio);
                    int left=(getWidth()-drawW)/2,top=(getHeight()-drawH)/2;
                    g.drawImage(framebuffer,left,top,drawW,drawH,null);
                }
            }
            int midX=getWidth()/2,midY=getHeight()/2;
            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(25,34,39,160));
            g.drawLine(midX-10,midY+1,midX+10,midY+1);
            g.drawLine(midX+1,midY-10,midX+1,midY+10);
            g.setColor(new Color(251,249,223));
            g.drawLine(midX-10,midY,midX+10,midY);
            g.drawLine(midX,midY-10,midX,midY+10);
            g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,19));
            g.setColor(new Color(255,252,231));
            g.drawString("STRATAWILD",22,34);
            g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13));
            g.drawString(fps+" FPS   •   "+chunkCount+" чанков   •   F2: качество",22,56);
            int slot=40, spacing=9, total=8*slot+7*spacing;
            int x0=(getWidth()-total)/2, y=getHeight()-68;
            for(int i=1;i<=8;++i) {
                int x=x0+(i-1)*(slot+spacing);
                g.setColor(new Color(18,29,32,207));g.fillRoundRect(x-3,y-3,46,49,9,9);
                g.setColor(i==selected?new Color(249,227,156):new Color(130,149,149));
                g.setStroke(new BasicStroke(i==selected?3:1));g.drawRoundRect(x-3,y-3,46,49,9,9);
                g.setColor(SWATCHES[i]);g.fillRect(x+7,y+6,25,25);
                g.setFont(new Font(Font.MONOSPACED,Font.BOLD,11));
                g.setColor(Color.WHITE);g.drawString(String.valueOf(i),x+4,y+41);
                g.drawString(String.valueOf(inventory[i]),x+24,y+41);
            }
            g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,13));
            g.drawString("Выбрано: "+MATERIALS[selected],x0,y-13);
            String text=captured?"WASD — движение · Space — прыжок · ЛКМ/ПКМ — блоки · F5 — сохранить · Esc — мышь"
                    :"Нажми ЛКМ, чтобы управлять камерой · Esc — освободить курсор";
            g.drawString(text,22,getHeight()-18);
            if(System.currentTimeMillis()<noticeDeadline) {
                g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,17));
                int labelWidth=g.getFontMetrics().stringWidth(notice);
                g.drawString(notice,(getWidth()-labelWidth)/2,getHeight()/2+58);
            }
        } finally {g.dispose();}
    }

    private static int keyBit(int code) {
        return switch(code) {
            case KeyEvent.VK_W -> 1;
            case KeyEvent.VK_S -> 2;
            case KeyEvent.VK_A -> 4;
            case KeyEvent.VK_D -> 8;
            case KeyEvent.VK_SPACE -> 16;
            case KeyEvent.VK_SHIFT -> 32;
            default -> 0;
        };
    }
    @Override public void keyPressed(KeyEvent e) {
        int bit=keyBit(e.getKeyCode());
        if(bit!=0) keys.getAndUpdate(v->v|bit);
        if(e.getKeyCode()>=KeyEvent.VK_1 && e.getKeyCode()<=KeyEvent.VK_8)
            selected=e.getKeyCode()-KeyEvent.VK_0;
        if(e.getKeyCode()==KeyEvent.VK_ESCAPE) releaseMouse();
        if(e.getKeyCode()==KeyEvent.VK_F2) resolutionRequest.set(true);
        if(e.getKeyCode()==KeyEvent.VK_F5) saveRequest.set(true);
    }
    @Override public void keyReleased(KeyEvent e) {
        int bit=keyBit(e.getKeyCode());
        if(bit!=0) keys.getAndUpdate(v->v&~bit);
    }
    @Override public void keyTyped(KeyEvent e) { }
    @Override public void mousePressed(MouseEvent e) {
        if(!captured) {captureMouse();return;}
        if(SwingUtilities.isLeftMouseButton(e)) action.set(1);
        else if(SwingUtilities.isRightMouseButton(e)) action.set(2);
    }
    private void onMouseMotion(MouseEvent e) {
        if(!captured || !isShowing()) return;
        int dx,dy;
        if(robot!=null) {
            dx=e.getX()-getWidth()/2;
            dy=e.getY()-getHeight()/2;
        } else {
            if(lastMouseX<0) {lastMouseX=e.getX();lastMouseY=e.getY();return;}
            dx=e.getX()-lastMouseX;
            dy=e.getY()-lastMouseY;
            lastMouseX=e.getX();lastMouseY=e.getY();
        }
        if(dx==0 && dy==0) return;
        yaw+=dx*0.0025f;
        pitch=Math.max(-1.52f,Math.min(1.52f,pitch-dy*0.0025f));
        if(robot!=null) centerMouse();
    }
    @Override public void mouseMoved(MouseEvent e) {onMouseMotion(e);}
    @Override public void mouseDragged(MouseEvent e) {onMouseMotion(e);}
    @Override public void mouseReleased(MouseEvent e) { }
    @Override public void mouseClicked(MouseEvent e) { }
    @Override public void mouseEntered(MouseEvent e) { }
    @Override public void mouseExited(MouseEvent e) { }
    @Override public void focusGained(FocusEvent e) { }
    @Override public void focusLost(FocusEvent e) {releaseMouse();}
}
