package s2;

public class Adventure {
    static final int MAX_ITEMS = 3;
    static class Item {
        final String name;
        final int weight;
        Item(String name, int weight) { this.name = name; this.weight = weight; }
        public String toString() { return name; }
    }

    static class Room {
        final String name;
        final String text;
        final Room[] exits = new Room[4];
        final Item[] items = new Item[4];
        boolean locked;
        Room(String name, String text) { this.name = name; this.text = text; }

        static String dirName(int d) {
            switch (d) {
                case 0: return "north";
                case 1: return "east";
                case 2: return "south";
                default: return "west";
            }
        }

        static int dirIndex(String s) {
            for (int i = 0; i < 4; i++) if (dirName(i).equals(s)) return i;
            return -1;
        }

        void link(int dir, Room other) {
            exits[dir] = other;
            other.exits[(dir + 2) % 4] = this;
        }

        String exitList() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (exits[i] != null) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(dirName(i));
                }
            }
            return sb.toString();
        }

        String itemList() {
            StringBuilder sb = new StringBuilder();
            for (Item it : items) {
                if (it != null) sb.append(sb.length() > 0 ? ", " : "").append(it.name);
            }
            return sb.length() == 0 ? "nothing" : sb.toString();
        }

        boolean put(Item it) {
            for (int i = 0; i < items.length; i++) {
                if (items[i] == null) { items[i] = it; return true; }
            }
            return false;
        }

        Item take(String what) {
            for (int i = 0; i < items.length; i++) {
                if (items[i] != null && items[i].name.equals(what)) {
                    Item it = items[i];
                    items[i] = null;
                    return it;
                }
            }
            return null;
        }
    }

    static class Game {
        Room here;
        final Item[] inventory = new Item[MAX_ITEMS];
        int moves;
        int score;
        boolean over;

        int carried() {
            int w = 0;
            for (Item it : inventory) if (it != null) w += it.weight;
            return w;
        }

        boolean holds(String what) {
            for (Item it : inventory) if (it != null && it.name.equals(what)) return true;
            return false;
        }

        void look() {
            System.out.println("[" + here.name + "] " + here.text);
            System.out.println("  exits: " + here.exitList() + "; items: " + here.itemList());
        }

        void go(String dirWord) {
            int d = Room.dirIndex(dirWord);
            if (d < 0) { System.out.println("  go where?"); return; }
            Room next = here.exits[d];
            if (next == null) { System.out.println("  you cannot go " + dirWord); return; }
            if (next.locked) { System.out.println("  the " + next.name + " is locked"); return; }
            here = next;
            moves++;
            look();
        }

        void get(String what) {
            Item it = here.take(what);
            if (it == null) { System.out.println("  no " + what + " here"); return; }
            for (int i = 0; i < inventory.length; i++) {
                if (inventory[i] == null) {
                    inventory[i] = it;
                    score += it.weight;
                    System.out.println("  taken " + what + " (weight now " + carried() + ")");
                    return;
                }
            }
            here.put(it);
            System.out.println("  your hands are full");
        }

        void drop(String what) {
            for (int i = 0; i < inventory.length; i++) {
                if (inventory[i] != null && inventory[i].name.equals(what)) {
                    here.put(inventory[i]);
                    inventory[i] = null;
                    System.out.println("  dropped " + what);
                    return;
                }
            }
            System.out.println("  you have no " + what);
        }

        void use(String what, Room vault) {
            if (!holds(what)) { System.out.println("  you have no " + what); return; }
            if (what.equals("key") && here.exits[1] == vault && vault.locked) {
                vault.locked = false;
                System.out.println("  the vault door clicks open");
            } else if (what.equals("coin") && here.name.equals("vault")) {
                over = true;
                System.out.println("  you win with score " + (score + 100));
            } else {
                System.out.println("  nothing happens");
            }
        }

        void inventoryList() {
            StringBuilder sb = new StringBuilder();
            for (Item it : inventory) if (it != null) sb.append(sb.length() > 0 ? ", " : "").append(it);
            System.out.println("  carrying: " + (sb.length() == 0 ? "nothing" : sb) + " weight " + carried());
        }
    }

    public static void main(String[] args) {
        Room hall = new Room("hall", "A dusty hall.");
        Room kitchen = new Room("kitchen", "Smells of old soup.");
        Room cellar = new Room("cellar", "Cold and dark.");
        Room vault = new Room("vault", "Gold glitters.");
        hall.link(0, kitchen);
        hall.link(2, cellar);
        kitchen.link(1, vault);
        vault.locked = true;
        kitchen.put(new Item("lamp", 2));
        kitchen.put(new Item("knife", 1));
        cellar.put(new Item("key", 1));
        cellar.put(new Item("anvil", 50));
        vault.put(new Item("coin", 1));

        String[] script = {
            "look", "go west", "go north", "get lamp", "get knife", "get spoon", "inventory", "go east",
            "go south", "go south", "get key", "get anvil", "drop lamp", "go north", "go north",
            "use key", "use key", "go east", "get coin", "use coin", "dance", "go up", "look"
        };
        Game g = new Game();
        g.here = hall;
        g.look();
        for (String line : script) {
            if (g.over) { System.out.println("> " + line + " (ignored, game over)"); continue; }
            System.out.println("> " + line);
            int sp = line.indexOf(' ');
            String verb = sp < 0 ? line : line.substring(0, sp);
            String noun = sp < 0 ? "" : line.substring(sp + 1);
            switch (verb) {
                case "look": g.look(); break;
                case "go": g.go(noun); break;
                case "get": g.get(noun); break;
                case "drop": g.drop(noun); break;
                case "use": g.use(noun, vault); break;
                case "inventory": g.inventoryList(); break;
                default: System.out.println("  I do not understand '" + verb + "'");
            }
        }
        System.out.println("moves " + g.moves + " score " + g.score + " over " + g.over);
        System.out.println("done");
    }
}
