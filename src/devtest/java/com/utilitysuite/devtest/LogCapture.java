package com.utilitysuite.devtest;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures ERROR (and WARN with a throwable) log events from every logger, so each scenario can report the exceptions
 * that happened while it ran. Events are buffered under a lock (they arrive from any thread) and drained on the client
 * thread.
 */
final class LogCapture extends AbstractAppender {

    private final List<String> pending = new ArrayList<>();
    private final List<String> all = new ArrayList<>();

    private LogCapture() {
        super("UtilitySuitePlaytestCapture", null, null, true, Property.EMPTY_ARRAY);
    }

    static LogCapture install() {
        LogCapture c = new LogCapture();
        c.start();
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration cfg = ctx.getConfiguration();
        LoggerConfig root = cfg.getRootLogger();
        root.addAppender(c, Level.WARN, null);
        ctx.updateLoggers();
        return c;
    }

    @Override
    public void append(LogEvent event) {
        Level lvl = event.getLevel();
        Throwable t = event.getThrown();
        boolean interesting = lvl.isMoreSpecificThan(Level.ERROR) || (t != null && lvl.isMoreSpecificThan(Level.WARN));
        String logger = event.getLoggerName() == null ? "" : event.getLoggerName();
        if (!interesting && !logger.startsWith("utilitysuite") && !logger.startsWith("com.utilitysuite")) {
            return;
        }
        String msg = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        StringBuilder sb = new StringBuilder().append(lvl).append(' ').append(logger).append(": ").append(msg);
        if (t != null) {
            sb.append(" | ").append(t);
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(4, st.length); i++) {
                sb.append(" @ ").append(st[i]);
            }
        }
        synchronized (this) {
            pending.add(sb.toString());
            all.add(sb.toString());
        }
    }

    synchronized List<String> drain() {
        List<String> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    synchronized List<String> allEvents() {
        return new ArrayList<>(all);
    }
}
