package com.utilitysuite.travel.movement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebuilds a {@code .jumpcap} file (see {@code tools.commands.JumpCapture}) as a {@link ShapeGrid}, block for block with
 * the exact collision boxes. Cells are placed relative to the captured feet block, shifted up so the feet sit at
 * {@link #FEET_Y} (the height the technique tests start from); a catch floor at y 5 stops long falls.
 */
final class JumpCapScene {

    static final int FEET_Y = 20;

    private static final Pattern BOXES = Pattern.compile("\"boxes\": \\[(.*?)\\]}");
    private static final Pattern NUM = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Pattern CELL = Pattern.compile("\\[(-?\\d+),(-?\\d+),(-?\\d+),(\\d+)]");

    final ShapeGrid grid = new ShapeGrid();
    /** Player position relative to the feet block's corner, in grid coordinates. */
    final double x;
    final double y;
    final double z;
    final float yaw;

    private JumpCapScene(String json) {
        List<float[]> palette = new ArrayList<>();
        Matcher pm = BOXES.matcher(json.substring(json.indexOf("\"palette\"")));
        while (pm.find()) {
            Matcher nm = NUM.matcher(pm.group(1));
            List<Float> f = new ArrayList<>();
            while (nm.find()) {
                f.add(Float.parseFloat(nm.group()));
            }
            float[] b = new float[f.size()];
            for (int i = 0; i < b.length; i++) {
                b[i] = f.get(i);
            }
            palette.add(b);
        }
        Matcher cm = CELL.matcher(json.substring(json.indexOf("\"cells\"")));
        while (cm.find()) {
            float[] b = palette.get(Integer.parseInt(cm.group(4)));
            if (b.length > 0) {
                grid.boxes(Integer.parseInt(cm.group(1)), Integer.parseInt(cm.group(2)) + FEET_Y,
                        Integer.parseInt(cm.group(3)), b);
            }
        }
        grid.fill(-12, 5, -12, 12, 5, 12);
        Matcher om = Pattern.compile("\"origin\": \\[(-?\\d+), (-?\\d+), (-?\\d+)]").matcher(json);
        if (!om.find()) {
            throw new IllegalArgumentException("no origin");
        }
        String player = json.substring(json.indexOf("\"player\""));
        x = dbl(player, "x") - Integer.parseInt(om.group(1));
        y = dbl(player, "y") - Integer.parseInt(om.group(2)) + FEET_Y;
        z = dbl(player, "z") - Integer.parseInt(om.group(3));
        yaw = (float) dbl(player, "yaw");
    }

    static JumpCapScene load(String resource) {
        try (InputStream in = JumpCapScene.class.getResourceAsStream("/jumpcap/" + resource)) {
            if (in == null) {
                throw new IllegalArgumentException("no test resource jumpcap/" + resource);
            }
            return new JumpCapScene(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double dbl(String player, String name) {
        Matcher m = Pattern.compile("\"" + name + "\": (-?\\d+(?:\\.\\d+)?)").matcher(player);
        if (!m.find()) {
            throw new IllegalArgumentException("no player " + name);
        }
        return Double.parseDouble(m.group(1));
    }
}
