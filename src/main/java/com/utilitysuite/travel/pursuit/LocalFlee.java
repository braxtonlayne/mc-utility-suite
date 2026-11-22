package com.utilitysuite.travel.pursuit;

/** Local (within ~12 blocks) escape plan away from one threat. */
public interface LocalFlee {

    /**
     * Plans from the body's position away from the threat.
     *
     * @return number of packed cells written to {@code out} (start first); 0 when no plan beats standing still
     */
    int plan(double threatX, double threatY, double threatZ, long[] out);
}
