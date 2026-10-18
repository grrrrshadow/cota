package com.winlator.xserver;

import com.winlator.core.Bitmask;
import com.winlator.xserver.events.Event;
import com.winlator.xserver.events.PointerWindowEvent;

public class GrabManager implements WindowManager.OnWindowModificationListener {
    private Window window;
    private boolean ownerEvents;
    private boolean releaseWithButtons;
    private EventListener eventListener;
    private boolean buttonGrabKeptForWarp;
    private final XServer xServer;

    public GrabManager(XServer xServer) {
        this.xServer = xServer;
        xServer.windowManager.addOnWindowModificationListener(this);
    }

    @Override
    public void onUnmapWindow(Window window) {
        if (window != null && window.getMapState() != Window.MapState.VIEWABLE) {
            deactivatePointerGrab();
        }
    }

    public Window getWindow() {
        return window;
    }

    public boolean isOwnerEvents() {
        return ownerEvents;
    }

    public boolean isReleaseWithButtons() {
        return releaseWithButtons;
    }

    public EventListener getEventListener() {
        return eventListener;
    }

    public XClient getClient() {
        return eventListener != null ? eventListener.client : null;
    }

    public void deactivatePointerGrab() {
        buttonGrabKeptForWarp = false;
        if (window != null) {
            xServer.inputDeviceManager.sendEnterLeaveNotify(window, xServer.inputDeviceManager.getPointWindow(), PointerWindowEvent.Mode.UNGRAB);
            window = null;
            eventListener = null;
        }
    }

    // winex11's SetCursorPos grabs its root window around every XWarpPointer (wine-10.10 mouse.c:1394-1408).
    // Replacing a held button's grab with it would send that button's release to the root window, which
    // winex11 drops in the game's process (event.c:455, mouse.c:550), so the button stayed down in Wine.
    public boolean keepButtonGrab(Window grabWindow, XClient client) {
        if (window == null || !releaseWithButtons || getClient() != client) return false;
        if (xServer.pointer.getButtonMask().isEmpty() || !grabWindow.isAncestorOf(window)) return false;
        buttonGrabKeptForWarp = true;
        return true;
    }

    public void ungrabPointer(XClient client) {
        if (buttonGrabKeptForWarp && getClient() == client) {
            buttonGrabKeptForWarp = false;
            return;
        }
        deactivatePointerGrab();
    }

    private void activatePointerGrab(Window window, EventListener eventListener, boolean ownerEvents, boolean releaseWithButtons) {
        if (this.window == null) {
            xServer.inputDeviceManager.sendEnterLeaveNotify(xServer.inputDeviceManager.getPointWindow(), window, PointerWindowEvent.Mode.GRAB);
        }
        this.window = window;
        this.releaseWithButtons = releaseWithButtons;
        this.ownerEvents = ownerEvents;
        this.eventListener = eventListener;
    }

    public void activatePointerGrab(Window window, boolean ownerEvents, Bitmask eventMask, XClient client) {
        activatePointerGrab(window, new EventListener(client, eventMask), ownerEvents, false);
    }

    public void activatePointerGrab(Window window) {
        EventListener eventListener = window.getButtonPressListener();
        activatePointerGrab(window, eventListener, eventListener.isInterestedIn(Event.OWNER_GRAB_BUTTON), true);
    }
}
