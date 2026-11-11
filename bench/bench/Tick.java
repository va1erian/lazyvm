package bench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A game tick: entities in a list move through a world of chunks kept in a
 * map, ask for their bounds (a fresh rectangle each time), test each other
 * for overlap and read tiles. Virtual calls, getters, boxing, short-lived
 * allocation and HashMap lookups, in the proportions a small 2D game has.
 */
public final class Tick {
    static final class Box {
        final int x0;
        final int y0;
        final int x1;
        final int y1;

        Box(int x, int y, int w, int h) {
            x0 = x;
            y0 = y;
            x1 = x + w;
            y1 = y + h;
        }

        boolean overlaps(Box o) {
            return x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1;
        }
    }

    static final class Chunk {
        final short[] tiles = new short[16 * 16];

        Chunk(int seed) {
            for (int i = 0; i < tiles.length; i++) {
                tiles[i] = (short) ((seed * 31 + i * 7) & 15);
            }
        }

        int tile(int x, int y) {
            return tiles[(y & 15) * 16 + (x & 15)];
        }
    }

    static final class World {
        private final Map<Long, Chunk> chunks = new HashMap<>();

        Chunk chunk(int cx, int cy) {
            Long key = ((long) cx << 32) ^ (cy & 0xffffffffL);
            Chunk c = chunks.get(key);
            if (c == null) {
                c = new Chunk(cx * 17 + cy);
                chunks.put(key, c);
            }
            return c;
        }

        int tile(int x, int y) {
            return chunk(x >> 4, y >> 4).tile(x, y);
        }
    }

    abstract static class Entity {
        int x;
        int y;
        int dx;
        int dy;
        int hits;

        Entity(int x, int y, int dx, int dy) {
            this.x = x;
            this.y = y;
            this.dx = dx;
            this.dy = dy;
        }

        Box bounds() {
            return new Box(x - radius(), y - radius(), 2 * radius(), 2 * radius());
        }

        abstract int radius();

        void tick(World w) {
            int nx = x + dx;
            int ny = y + dy;
            if (w.tile(nx >> 2, ny >> 2) < 12) {
                x = nx;
                y = ny;
            } else {
                dx = -dx;
                dy = -dy;
            }
        }
    }

    static final class Mob extends Entity {
        Mob(int x, int y, int dx, int dy) {
            super(x, y, dx, dy);
        }

        int radius() {
            return 4;
        }
    }

    static final class Item extends Entity {
        Item(int x, int y) {
            super(x, y, 0, 1);
        }

        int radius() {
            return 2;
        }
    }

    private Tick() {
    }

    public static void main(String[] args) {
        int ticks = 600 * Report.scale(args);
        World world = new World();
        List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            entities.add((i % 3) == 0 ? new Item(i * 13, i * 7) : new Mob(i * 11, i * 5, (i % 5) - 2, (i % 7) - 3));
        }
        long start = System.nanoTime();
        long sum = 0;
        for (int t = 0; t < ticks; t++) {
            for (Entity e : entities) {
                e.tick(world);
            }
            Box player = new Box(t & 255, (t >> 2) & 255, 16, 16);
            for (Entity e : entities) {
                if (e.bounds().overlaps(player)) {
                    e.hits++;
                    sum += e.x;
                }
            }
            for (int i = -12; i < 12; i++) {
                for (int j = -8; j < 8; j++) {
                    sum += world.tile((t >> 3) + i, j);
                }
            }
        }
        for (Entity e : entities) {
            sum = sum * 31 + e.x * 7 + e.y + e.hits;
        }
        Report.print("tick", sum, start);
    }
}
