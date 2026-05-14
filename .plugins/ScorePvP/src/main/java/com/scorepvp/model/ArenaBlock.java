package com.scorepvp.model;

import org.bukkit.block.data.BlockData;

/**
 * Stores the state of one block for vanilla arena restoration.
 * rel = relative coordinates from corner1 (dx, dy, dz).
 */
public class ArenaBlock {

    private final int      relX, relY, relZ;
    private final BlockData blockData;
    private final String   tileEntityJson;  // null if not a tile entity

    public ArenaBlock(int relX, int relY, int relZ,
                      BlockData blockData, String tileEntityJson) {
        this.relX           = relX;
        this.relY           = relY;
        this.relZ           = relZ;
        this.blockData      = blockData;
        this.tileEntityJson = tileEntityJson;
    }

    public int       getRelX()            { return relX; }
    public int       getRelY()            { return relY; }
    public int       getRelZ()            { return relZ; }
    public BlockData getBlockData()       { return blockData; }
    public String    getTileEntityJson()  { return tileEntityJson; }
    public boolean   hasTileEntity()      { return tileEntityJson != null; }
}
