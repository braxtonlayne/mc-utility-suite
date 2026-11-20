package com.utilitysuite.core.api;

/** Which world knowledge a consumer may use. */
public enum DataSourceMode {
    /** Only blocks actually seen in loaded chunks. */
    OBSERVED_ONLY,
    /** Only seed-based predictions. */
    SIMULATED_ONLY,
    /** Predictions guide exploration; observations confirm or refute them. */
    HYBRID
}
