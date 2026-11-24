package com.utilitysuite.travel.pursuit;

/** What the run core reads about a route cell to notice it was obstructed (engine-free; the game side reads blocks). */
public interface CellProbe {

    /** Feet and head cells can be walked through. */
    int CLEAR = 1;
    /** The cell below the feet carries the body. */
    int FLOOR = 2;

    /**
     * @param cell packed feet cell ({@code BlockPos.asLong} layout)
     * @return {@link #CLEAR} | {@link #FLOOR} bits for the cell as it is now. Allocation free.
     */
    int cellBits(long cell);
}