package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IBlockWork;

/** Publishes the field modules' lease-checked block hands ({@link FieldHands}) as the core {@link IBlockWork} service. */
public final class FieldBlockWork implements IBlockWork {

    @Override
    public Hands hands(String ownerId, ControlPriority priority) {
        return new FieldHands(ownerId, priority);
    }
}
