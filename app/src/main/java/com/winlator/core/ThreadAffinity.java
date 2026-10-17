package com.winlator.core;

public abstract class ThreadAffinity {
    static {
        System.loadLibrary("winlator");
    }

    /** Pins the calling thread to the CPUs in mask and returns its previous mask, or 0 if that failed. */
    public static native int setMask(int mask);
}
