package com.winlator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.winlator.core.Bitmask;
import com.winlator.xconnector.ConnectedClient;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowAttributes;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.events.Event;
import com.winlator.xserver.requests.GrabRequests;
import com.winlator.xserver.requests.WindowRequests;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Stuck right mouse button (OpenTTD Decouple, mouse logs 2026-10-17): the game pins the pointer
 * during a right-drag and calls SetCursorPos on every WM_MOUSEMOVE. winex11 (wine-10.10
 * dlls/winex11.drv/mouse.c:1394-1408) wraps every warp in
 *   XGrabPointer(root_window, False, PointerMotion|ButtonPress|ButtonRelease) ; XWarpPointer ; XUngrabPointer
 * and in Winlator's "explorer /desktop=" mode root_window is the virtual-desktop X window
 * (desktop.c:57, window.c:2637-2638). A ButtonRelease that Winlator reports relative to that window
 * is mapped to the desktop hwnd (event.c:455) and dropped in the game process (mouse.c:550).
 *
 * Runs the real request handlers (GrabRequests / WindowRequests) under the same locks as
 * XClientRequestHandler.java:264-273 and injects the button the way InputControlsView does
 * (XServer.injectPointerButtonPress/Release). The native X streams are replaced by recorders.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, instrumentedPackages = {"com.winlator.xconnector", "com.winlator.xserver"})
public class StuckRightButtonGrabTest {
    // XOutputStream that keeps what the server writes to the client (replies and events, 32 bytes each here)
    static class RecOut extends XOutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RecOut() { super(-1, 0); }
        @Override public void writeByte(byte v) { bytes.write(v); }
        @Override public void writeShort(short v) { bytes.write(v); bytes.write(v >> 8); }
        @Override public void writeInt(int v) { for (int i = 0; i < 4; i++) bytes.write(v >> (8 * i)); }
        @Override public void writeLong(long v) { writeInt((int)v); writeInt((int)(v >> 32)); }
        @Override public void write(byte[] d, int off, int len) { bytes.write(d, off, len - off); }
        @Override public void writePad(int n) { for (int i = 0; i < n; i++) bytes.write(0); }
        @Override public XStreamLock lock() { return () -> {}; }
        List<byte[]> messages() {
            byte[] all = bytes.toByteArray();
            List<byte[]> r = new ArrayList<>();
            for (int i = 0; i + 32 <= all.length; i += 32) {
                byte[] m = new byte[32];
                System.arraycopy(all, i, m, 0, 32);
                r.add(m);
            }
            return r;
        }
    }

    // XInputStream fed with one request body
    static class RecIn extends XInputStream {
        ByteBuffer buf = ByteBuffer.allocate(0);
        RecIn() { super(-1, 0); }
        void feed(byte[] b) { buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); }
        @Override public byte readByte() { return buf.get(); }
        @Override public int readUnsignedByte() { return Byte.toUnsignedInt(buf.get()); }
        @Override public short readShort() { return buf.getShort(); }
        @Override public int readUnsignedShort() { return Short.toUnsignedInt(buf.getShort()); }
        @Override public int readInt() { return buf.getInt(); }
        @Override public long readUnsignedInt() { return Integer.toUnsignedLong(buf.getInt()); }
        @Override public void skip(int n) { buf.position(buf.position() + n); }
    }

    static final int BUTTON_PRESS = 4, BUTTON_RELEASE = 5, ENTER_NOTIFY = 7;
    static final XServer.Lockable[] GRAB_LOCKS = {XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE, XServer.Lockable.CURSOR_MANAGER};
    static final XServer.Lockable[] WM_INPUT = {XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE};

    interface Handler { void run(XClient c, XInputStream in, XOutputStream out) throws Exception; }

    XServer xServer;
    XClient explorer, game;
    Window desktop, whole, clientWindow;

    static void setField(Object o, Class<?> c, String name, Object v) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    XClient newClient() throws Exception {
        XClient c = new XClient(0, -1, xServer);
        setField(c, ConnectedClient.class, "inputStream", new RecIn());
        setField(c, ConnectedClient.class, "outputStream", new RecOut());
        return c;
    }

    @Before
    public void setUp() throws Exception {
        xServer = new XServer(null, new ScreenInfo(1280, 720));
        explorer = newClient();
        game = newClient();
        try (XLock lock = xServer.lockAll()) {
            Window root = xServer.windowManager.rootWindow;
            // virtual desktop = Wine's root_window, owned by explorer.exe (desktop.c:75-77)
            desktop = xServer.windowManager.createWindow(0x200001, root, (short)0, (short)0, (short)1280, (short)720, WindowAttributes.WindowClass.INPUT_ONLY, null, (byte)0, explorer);
            explorer.setEventListenerForWindow(desktop, new Bitmask(Event.EXPOSURE | Event.KEY_PRESS | Event.KEY_RELEASE | Event.ENTER_WINDOW |
                Event.POINTER_MOTION | Event.BUTTON_PRESS | Event.BUTTON_RELEASE | Event.FOCUS_CHANGE | Event.STRUCTURE_NOTIFY | Event.PROPERTY_CHANGE));
            xServer.windowManager.mapWindow(desktop);
            // the game's whole_window (window.c:496-499) and its GL client_window (window.c:2202)
            whole = xServer.windowManager.createWindow(0x400001, desktop, (short)0, (short)0, (short)1280, (short)720, WindowAttributes.WindowClass.INPUT_ONLY, null, (byte)0, game);
            game.setEventListenerForWindow(whole, new Bitmask(Event.EXPOSURE | Event.POINTER_MOTION | Event.BUTTON_PRESS | Event.BUTTON_RELEASE |
                Event.ENTER_WINDOW | Event.KEY_PRESS | Event.KEY_RELEASE | Event.FOCUS_CHANGE | Event.KEYMAP_STATE | Event.STRUCTURE_NOTIFY | Event.PROPERTY_CHANGE));
            clientWindow = xServer.windowManager.createWindow(0x400002, whole, (short)0, (short)0, (short)1280, (short)720, WindowAttributes.WindowClass.INPUT_ONLY, null, (byte)0, game);
            game.setEventListenerForWindow(clientWindow, new Bitmask(Event.EXPOSURE));
            xServer.windowManager.mapWindow(whole);
            xServer.windowManager.mapWindow(clientWindow);
        }
        xServer.injectPointerMove(488, 353);
    }

    // what XClientRequestHandler does around one request: its locks, then the handler
    void request(XClient c, byte data, byte[] body, XServer.Lockable[] locks, Handler h) throws Exception {
        try (XLock lock = xServer.lock(locks)) {
            ((RecIn)c.getInputStream()).feed(body);
            c.generateSequenceNumber();
            c.setRequestData(data);
            h.run(c, c.getInputStream(), c.getOutputStream());
        }
    }

    static ByteBuffer body(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }

    /** X11DRV_SetCursorPos (mouse.c:1394-1408); duringGrab runs while Wine waits for the GrabPointer reply. */
    int wineSetCursorPos(int x, int y, Runnable duringGrab) throws Exception {
        RecOut out = (RecOut)game.getOutputStream();
        int before = out.messages().size();
        ByteBuffer g = body(20);
        g.putInt(desktop.id);
        g.putShort((short)(Event.POINTER_MOTION | Event.BUTTON_PRESS | Event.BUTTON_RELEASE));
        request(game, (byte)0 /* owner_events False */, g.array(), GRAB_LOCKS, GrabRequests::grabPointer);
        int status = -1;
        List<byte[]> m = out.messages();
        for (int i = before; i < m.size(); i++) if (m.get(i)[0] == 1) status = m.get(i)[1];
        if (status != 0) return status; // Wine: "refusing to warp pointer ... without exclusive grab"

        if (duringGrab != null) duringGrab.run();

        ByteBuffer w = body(20);
        w.putInt(desktop.id); w.putInt(desktop.id);
        w.putShort((short)0); w.putShort((short)0); w.putShort((short)0); w.putShort((short)0);
        w.putShort((short)x); w.putShort((short)y);
        request(game, (byte)0, w.array(), WM_INPUT, WindowRequests::warpPointer);
        request(game, (byte)0, new byte[4], WM_INPUT, GrabRequests::ungrabPointer);
        return status;
    }

    static int eventWindow(byte[] e) { return ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN).getInt(12); }

    /** event windows of the button-3 events of one type the client got from index 'from' on */
    static List<Integer> buttonEvents(XClient c, int type, int from) {
        List<Integer> r = new ArrayList<>();
        List<byte[]> m = ((RecOut)c.getOutputStream()).messages();
        for (int i = from; i < m.size(); i++) if ((m.get(i)[0] & 0x7f) == type && m.get(i)[1] == 3) r.add(eventWindow(m.get(i)));
        return r;
    }

    static int count(XClient c, int type, int from) {
        int n = 0;
        List<byte[]> m = ((RecOut)c.getOutputStream()).messages();
        for (int i = from; i < m.size(); i++) if ((m.get(i)[0] & 0x7f) == type) n++;
        return n;
    }

    static int size(XClient c) { return ((RecOut)c.getOutputStream()).messages().size(); }

    void pressRight() { xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT); }
    void releaseRight() { xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT); }

    @Test
    public void releaseDuringWarpGrabIsReportedOnTheGameWindow() throws Exception {
        int g0 = size(game);
        pressRight();
        assertEquals(java.util.Collections.singletonList(whole.id), buttonEvents(game, BUTTON_PRESS, g0));
        xServer.injectPointerMoveDelta(-1, 0);                // finger moves: WM_MOUSEMOVE -> SetCursorPos
        assertEquals(0, wineSetCursorPos(488, 353, null));
        xServer.injectPointerMoveDelta(-1, 0);
        // the finger lifts while the next SetCursorPos waits for its GrabPointer reply
        assertEquals(0, wineSetCursorPos(488, 353, this::releaseRight));

        assertFalse(xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT));
        // stock 11.2: [0x200001] (desktop = Wine root_window) -> dropped by winex11 -> WM_RBUTTONUP never comes
        assertEquals(java.util.Collections.singletonList(whole.id), buttonEvents(game, BUTTON_RELEASE, g0));
        assertTrue(buttonEvents(explorer, BUTTON_RELEASE, 0).isEmpty());
    }

    @Test
    public void heldButtonKeepsItsGrabAcrossWarps() throws Exception {
        pressRight();
        assertSame(whole, xServer.grabManager.getWindow());
        int e0 = size(explorer);
        for (int i = 0; i < 3; i++) {
            xServer.injectPointerMoveDelta(-1, -1);
            assertEquals(0, wineSetCursorPos(488, 353, null));
            // stock 11.2: grab = none after the first warp, although R is still held
            assertSame("after warp " + (i + 1), whole, xServer.grabManager.getWindow());
            assertTrue(xServer.grabManager.isReleaseWithButtons());
        }
        // stock 11.2: every warp from the second on sends explorer a GRAB EnterNotify; explorer then moves the
        // Wine cursor back to the pre-warp point (the doubled "ukazatel vracen" lines in the game's mouse log)
        assertEquals(0, count(explorer, ENTER_NOTIFY, e0));
        int g0 = size(game);
        releaseRight();
        assertEquals(java.util.Collections.singletonList(whole.id), buttonEvents(game, BUTTON_RELEASE, g0));
        assertNull(xServer.grabManager.getWindow());
    }

    @Test
    public void releaseOutsideTheWarpGrabReachesTheGameWindow() throws Exception {
        pressRight();
        xServer.injectPointerMoveDelta(2, 1);
        assertEquals(0, wineSetCursorPos(488, 353, null));
        int g0 = size(game);
        releaseRight();
        assertEquals(java.util.Collections.singletonList(whole.id), buttonEvents(game, BUTTON_RELEASE, g0));
    }

    @Test
    public void warpWithNoButtonHeldStillGrabsAndUngrabsTheRoot() throws Exception {
        final Window[] during = new Window[1];
        assertEquals(0, wineSetCursorPos(600, 300, () -> during[0] = xServer.grabManager.getWindow()));
        assertSame(desktop, during[0]);
        assertNull(xServer.grabManager.getWindow());
        assertEquals(600, xServer.pointer.getX());
    }

    @Test
    public void rePressAfterLostReleaseIsAFreshButtonPress() throws Exception {
        // the logs' tail: Winlator's Pointer is up, so the user's next press goes out as a new ButtonPress
        int g0 = size(game);
        pressRight();
        xServer.injectPointerMoveDelta(-1, 0);
        wineSetCursorPos(488, 353, this::releaseRight);
        pressRight();
        assertEquals(2, buttonEvents(game, BUTTON_PRESS, g0).size());
        pressRight(); // a press while Pointer has R down is not a transition (Pointer.java:84)
        assertEquals(2, buttonEvents(game, BUTTON_PRESS, g0).size());
    }

    /** reply status of a GrabPointer(owner_events False) from client c on window w */
    int grabPointer(XClient c, Window w) throws Exception {
        RecOut out = (RecOut)c.getOutputStream();
        int before = out.messages().size();
        ByteBuffer g = body(20);
        g.putInt(w.id);
        g.putShort((short)(Event.POINTER_MOTION | Event.BUTTON_PRESS | Event.BUTTON_RELEASE));
        request(c, (byte)0, g.array(), GRAB_LOCKS, GrabRequests::grabPointer);
        List<byte[]> m = out.messages();
        for (int i = before; i < m.size(); i++) if (m.get(i)[0] == 1) return m.get(i)[1];
        return -1;
    }

    void ungrabPointer(XClient c) throws Exception {
        request(c, (byte)0, new byte[4], WM_INPUT, GrabRequests::ungrabPointer);
    }

    /** event windows of the events of one type for one button the client got from index 'from' on */
    static List<Integer> buttonEvents(XClient c, int type, int button, int from) {
        List<Integer> r = new ArrayList<>();
        List<byte[]> m = ((RecOut)c.getOutputStream()).messages();
        for (int i = from; i < m.size(); i++) if ((m.get(i)[0] & 0x7f) == type && m.get(i)[1] == button) r.add(eventWindow(m.get(i)));
        return r;
    }

    @Test
    public void secondButtonPressedAndBothReleasedDuringWarps() throws Exception {
        int g0 = size(game);
        pressRight();
        xServer.injectPointerMoveDelta(-1, 0);
        assertEquals(0, wineSetCursorPos(488, 353, () -> xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)));
        xServer.injectPointerMoveDelta(-1, 0);
        assertEquals(0, wineSetCursorPos(488, 353, () -> xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)));
        assertSame("R is still held", whole, xServer.grabManager.getWindow());
        xServer.injectPointerMoveDelta(-1, 0);
        assertEquals(0, wineSetCursorPos(488, 353, this::releaseRight));
        assertNull(xServer.grabManager.getWindow());

        assertEquals(java.util.Collections.singletonList(whole.id), buttonEvents(game, BUTTON_PRESS, 1, g0));
        assertEquals(java.util.Collections.singletonList(whole.id), buttonEvents(game, BUTTON_RELEASE, 1, g0));
        assertEquals(java.util.Collections.singletonList(whole.id), buttonEvents(game, BUTTON_RELEASE, 3, g0));
        assertTrue(buttonEvents(explorer, BUTTON_PRESS, 1, 0).isEmpty());

        // nothing is left kept: a warp with no button held grabs and ungrabs the root as before
        final Window[] during = new Window[1];
        assertEquals(0, wineSetCursorPos(600, 300, () -> during[0] = xServer.grabManager.getWindow()));
        assertSame(desktop, during[0]);
        assertNull(xServer.grabManager.getWindow());
    }

    @Test
    public void captureGrabOnTheGameWindowItselfOutlivesTheButton() throws Exception {
        // X11DRV_SetCapture (window.c:3039) grabs whole_window for menus and move/size while the button is down;
        // that is not an ancestor of the button's grab window, so it stays an active grab as before
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
        assertEquals(0, grabPointer(game, whole));
        assertFalse(xServer.grabManager.isReleaseWithButtons());
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        assertSame(whole, xServer.grabManager.getWindow());
        ungrabPointer(game);
        assertNull(xServer.grabManager.getWindow());
    }

    @Test
    public void otherClientsStillGetAlreadyGrabbedWhileTheButtonIsHeld() throws Exception {
        pressRight();
        assertEquals(1 /* AlreadyGrabbed */, grabPointer(explorer, desktop));
        final int[] status = new int[1];
        assertEquals(0, wineSetCursorPos(488, 353, () -> {
            try { status[0] = grabPointer(explorer, desktop); } catch (Exception e) { throw new RuntimeException(e); }
        }));
        assertEquals(1, status[0]);
        assertSame(whole, xServer.grabManager.getWindow());
        assertTrue(xServer.grabManager.isReleaseWithButtons());
    }

    @Test
    public void windowUnmappedDuringTheWarpLeavesNoKeptGrabBehind() throws Exception {
        Window popup;
        try (XLock lock = xServer.lockAll()) {
            popup = xServer.windowManager.createWindow(0x400003, desktop, (short)10, (short)10, (short)50, (short)50, WindowAttributes.WindowClass.INPUT_ONLY, null, (byte)0, game);
            xServer.windowManager.mapWindow(popup);
        }
        pressRight();
        assertEquals(0, wineSetCursorPos(488, 353, () -> {
            try (XLock lock = xServer.lockAll()) { xServer.windowManager.unmapWindow(popup); }
        }));
        // any unmap ends the pointer grab in Winlator (GrabManager.onUnmapWindow), the kept one included
        assertNull(xServer.grabManager.getWindow());
        int g0 = size(game);
        releaseRight();
        assertEquals(java.util.Collections.singletonList(whole.id), buttonEvents(game, BUTTON_RELEASE, g0));

        // the next drag gets a fresh button grab, kept through its warps and ended by its release
        pressRight();
        assertEquals(0, wineSetCursorPos(488, 353, null));
        assertSame(whole, xServer.grabManager.getWindow());
        releaseRight();
        assertNull(xServer.grabManager.getWindow());
    }

    @Test
    public void releaseInsideTheWarpDoesNotSwallowALaterUngrab() throws Exception {
        pressRight();
        assertEquals(0, wineSetCursorPos(488, 353, this::releaseRight));
        assertNull(xServer.grabManager.getWindow());
        // a later active grab (a menu's SetCapture) is still ended by its own UngrabPointer
        assertEquals(0, grabPointer(game, whole));
        assertSame(whole, xServer.grabManager.getWindow());
        ungrabPointer(game);
        assertNull(xServer.grabManager.getWindow());
    }
}
