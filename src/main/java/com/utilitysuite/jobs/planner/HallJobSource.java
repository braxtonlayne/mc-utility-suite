package com.utilitysuite.jobs.planner;

/**
 * Plans the "sort hall" goal (the Hall Sorter module: its radius, nearly-full share and on/off state). The executor
 * asks it on the client thread when the job starts and for previews; the plan holds HALL_TAKE / HALL_PUT /
 * HALL_MERGE steps.
 */
public interface HallJobSource {

    /**
     * @param plan  the steps (empty when the hall is already tidy); warnings are shown with the plan
     * @param error why the sort cannot run (sorter off, too few remembered chests), or null
     */
    record Result(Plan plan, String error) {
        public static Result error(String why) {
            return new Result(Plan.EMPTY, why);
        }
    }

    /**
     * Plans a sort around {@code from}. {@code forRun} is true when the job is starting (not a preview), so the source
     * may post its one-off notices.
     */
    Result hallPlan(String dimension, long from, boolean forRun);
}
