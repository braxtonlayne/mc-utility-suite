package com.utilitysuite.devtest;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;

/**
 * Dev-only: Baritone as the course's pathfinding-bot benchmark (CourseAuto mode {@code baritone}). Baritone is
 * configured to run parkour honestly: no breaking, no placing (so no parkour-place or pillaring), no inventory, no
 * water-bucket falls; sprinting, parkour and parkour-ascend on. Goals go through the suite's leased navigator like any
 * job, so the core's no-snap camera cap applies to Baritone too. Only called when Baritone is loaded (runCourse has it
 * on the dev runtime). The one devtest class that imports {@code baritone.*}.
 */
final class BaritoneBench {

    private BaritoneBench() {
    }

    static void configure() {
        Settings s = BaritoneAPI.getSettings();
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.allowInventory.value = false;
        s.allowWaterBucketFall.value = false;
        s.allowParkour.value = true;
        s.allowParkourAscend.value = true;
        s.allowParkourPlace.value = false;
        s.allowSprint.value = true;
        s.allowDiagonalAscend.value = true;
        s.allowDiagonalDescend.value = true;
        s.chatDebug.value = true; // path failures and their reasons reach the log as chat
    }
}
