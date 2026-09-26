package com.mdvcraft.mdvnpc.integration;

/** Separación pequeña y comprobable de las condiciones de la excepción. */
public final class SpawnBypassPolicy {
    private SpawnBypassPolicy() { }
    public static boolean shouldOverride(boolean cancelledAtLowest, boolean cancelledNow, boolean regionBlocks) {
        return !cancelledAtLowest && cancelledNow && regionBlocks;
    }
}
