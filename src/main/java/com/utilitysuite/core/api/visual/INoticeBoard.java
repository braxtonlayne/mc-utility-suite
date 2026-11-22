package com.utilitysuite.core.api.visual;

/** Short messages to the user (the replacement for chat and action-bar status text). Posting is not a hot path. */
public interface INoticeBoard {

    INoticeBoard NONE = (kind, title, detail) -> {
    };

    /** {@code detail} may be null. Identical titles within a short time merge into one notice with a counter. */
    void post(NoticeKind kind, String title, String detail);
}
