package com.utilitysuite.devtest;

/**
 * A flat stone test platform in the sky (25 x 25 at Y={@value #FLOOR}) built with /fill, so the schematic, travel,
 * combat and tools scenarios do not depend on the seed's terrain. Layout around the centre (cx, cz):
 * <ul>
 *   <li>schematic site: anchor (cx-8, FLOOR+1, cz-8), gather-only site (cx, FLOOR+1, cz-8)</li>
 *   <li>water pool: x cx+4..cx+8, z cz-8..cz-4 (one block deep, stone below)</li>
 *   <li>south edge (z = cz+12) for SafeWalk, east edge (x = cx+12) for the bridge: open air below</li>
 * </ul>
 */
final class Arena {

    static final int FLOOR = 150;
    static int cx;
    static int cz;
    static boolean built;

    private Arena() {
    }

    /**
     * Adds the steps that build the arena once (force-load its chunks, fill the deck, only then teleport onto it:
     * teleporting first dropped the player 150 blocks before the deck existed) and then put the player on it.
     */
    static void ensure(Scenario s) {
        s.run("arena: pick centre", () -> {
            if (!built) {
                cx = (int) Math.floor(BootScenario.spawn[0]);
                cz = (int) Math.floor(BootScenario.spawn[2]) + 48;
            }
        });
        s.step("arena: build", t -> {
            if (built) {
                return true;
            }
            switch (t) {
                case 0 -> Harness.command("forceload add " + (cx - 12) + " " + (cz - 12) + " " + (cx + 12) + " "
                        + (cz + 12));
                case 40 -> Harness.command(fill(-12, 1, -12, 12, 9, 12, "air"));
                case 42 -> Harness.command(fill(-12, 0, -12, 12, 0, 12, "stone"));
                case 44 -> Harness.command(fill(4, -1, -8, 8, -1, -4, "stone"));
                case 46 -> Harness.command(fill(4, 0, -8, 8, 0, -4, "water"));
                default -> {
                }
            }
            return t >= 50;
        });
        s.step("arena: go there", t -> {
            if (t == 0) {
                Harness.command("tp @s " + cx + ".5 " + (FLOOR + 1) + " " + cz + ".5 0 0");
            }
            if (t < 40) {
                return false;
            }
            if (!built) {
                built = true;
                s.check("arena built", H.blockId(cx, FLOOR, cz).equals("minecraft:stone")
                        && H.blockId(cx + 6, FLOOR, cz - 6).equals("minecraft:water"), "centre "
                        + H.blockId(cx, FLOOR, cz) + ", pool " + H.blockId(cx + 6, FLOOR, cz - 6) + " at " + cx
                        + "," + FLOOR + "," + cz + "; player " + H.posStr());
            }
            return true;
        });
    }

    static String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        return "fill " + (cx + x0) + " " + (FLOOR + y0) + " " + (cz + z0) + " " + (cx + x1) + " " + (FLOOR + y1) + " "
                + (cz + z1) + " " + block;
    }

    static String tp(double dx, double dz, float yaw) {
        return "tp @s " + (cx + dx) + " " + (FLOOR + 1) + " " + (cz + dz) + " " + yaw + " 0";
    }
}
