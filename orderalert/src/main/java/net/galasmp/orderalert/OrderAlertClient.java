package net.galasmp.orderalert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OrderAlert: meldet sich, wenn im Order-Menue ein Item zu einem bestimmten Preis gesucht wird.
 *   /orderalert add <Mindestpreis> <Item>   z.B. /orderalert add 1.5k diamond
 *   /orderalert list | remove <Nr> | clear | on | off
 * Gelesen wird nur, was du gerade im Menue siehst.
 */
public final class OrderAlertClient implements ClientModInitializer {

    public static final class Rule {
        /** Anzeigename, z.B. "Wurftrank des Schleimens" */
        public String item;
        /** Item-ID, z.B. "minecraft:diamond" (leer bei per Befehl angelegten Regeln) */
        public String id = "";
        /** true = Item ohne Varianten (dann reicht die ID, auch bei anderer Sprache) */
        public boolean plain;
        public double minPrice;
    }

    public static final class Config {
        public int version = 0;
        public boolean enabled = true;
        /** Das Menue gilt als Order-Menue, wenn sein Titel eines dieser Woerter enthaelt */
        public List<String> titleContains = new ArrayList<>(List.of("order", "auftr"));
        /** Lore-Zeilen mit diesen Woertern enthalten den Preis */
        public List<String> priceLineContains = new ArrayList<>(List.of("preis pro", "price per", "preis", "price", "$"));
        /** Auch ohne offenes Menue pruefen: /orders im Hintergrund oeffnen, lesen, schliessen */
        public boolean backgroundCheck = true;
        public int intervalSeconds = 15;
        public String command = "order";
        public List<Rule> rules = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d.,]*)\\s*([kKmMbBtT]?)");
    public static Config config = new Config();
    /** Schon gemeldete Orders -> Zeitpunkt (nach 30 Minuten wieder erlaubt) */
    private static final java.util.Map<String, Long> alerted = new java.util.HashMap<>();
    private static int tick;
    private static long nextBackground;
    /** Wir haben gerade selbst /orders geschickt - das naechste Menue gehoert uns */
    public static long ownRequestUntil;
    /** ID des im Hintergrund geoeffneten Menues, -1 = keins */
    public static int hiddenContainer = -1;
    public static long hiddenSince;
    /** Letztes Paket fuer das versteckte Menue */
    public static long hiddenUpdated;
    /** Gesammelter Inhalt des versteckten Menues (oben Menue, unten 36 Slots Inventar) */
    public static List<ItemStack> hiddenItems = new ArrayList<>();
    /** "Jetzt pruefen": diese eine Pruefung ausfuehrlich im Chat zeigen */
    public static boolean verbose;
    private static long verboseUntil;
    private static boolean verboseAnswered;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("orderalert.json");
    }

    private static void load() {
        try {
            if (Files.exists(file())) {
                Config c = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Config.class);
                if (c != null) config = c;
            }
        } catch (Exception ignored) {
            // kaputte Datei: Standard nehmen
        }
        if (config.rules == null) config.rules = new ArrayList<>();
        // Alte Einstellungen: Befehl hiess faelschlich /orders
        if (config.version < 2) {
            if (config.command == null || config.command.equalsIgnoreCase("orders")) config.command = "order";
        }
        if (config.version < 3) {
            config.intervalSeconds = 15;   // neuer Standard: alle 15 Sekunden
            config.version = 3;
            save();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // nicht schlimm
        }
    }

    private static net.minecraft.client.KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        load();
        openKey = net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(new net.minecraft.client.KeyMapping(
                "key.orderalert.open", com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
                org.lwjgl.glfw.GLFW.GLFW_KEY_J, net.minecraft.client.KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) mc.setScreen(new OrderAlertScreen(mc.screen));
        });
        ClientTickEvents.END_CLIENT_TICK.register(OrderAlertClient::onTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("orderalert")
                        .executes(ctx -> { list(ctx.getSource()); return 1; })
                        .then(ClientCommandManager.literal("list").executes(ctx -> { list(ctx.getSource()); return 1; }))
                        .then(ClientCommandManager.literal("on").executes(ctx -> {
                            config.enabled = true; save(); ctx.getSource().sendFeedback(msg("§aOrderAlert ist an.")); return 1; }))
                        .then(ClientCommandManager.literal("off").executes(ctx -> {
                            config.enabled = false; save(); ctx.getSource().sendFeedback(msg("§cOrderAlert ist aus.")); return 1; }))
                        .then(ClientCommandManager.literal("clear").executes(ctx -> {
                            config.rules.clear(); save(); alerted.clear(); ctx.getSource().sendFeedback(msg("§fAlle Regeln geloescht.")); return 1; }))
                        .then(ClientCommandManager.literal("remove")
                                .then(ClientCommandManager.argument("nr", StringArgumentType.word()).executes(ctx -> {
                                    int nr;
                                    try { nr = Integer.parseInt(StringArgumentType.getString(ctx, "nr")); } catch (NumberFormatException e) { nr = -1; }
                                    if (nr < 1 || nr > config.rules.size()) {
                                        ctx.getSource().sendFeedback(msg("§cDiese Nummer gibt es nicht. §7/orderalert list"));
                                        return 0;
                                    }
                                    Rule r = config.rules.remove(nr - 1); save(); alerted.clear();
                                    ctx.getSource().sendFeedback(msg("§fEntfernt: §e" + r.item));
                                    return 1;
                                })))
                        .then(ClientCommandManager.literal("add")
                                .then(ClientCommandManager.argument("preis", StringArgumentType.word())
                                        .then(ClientCommandManager.argument("item", StringArgumentType.greedyString()).executes(ctx -> {
                                            double price = parseNumber(StringArgumentType.getString(ctx, "preis"));
                                            if (price < 0) {
                                                ctx.getSource().sendFeedback(msg("§cUngueltiger Preis. Beispiel: §f/orderalert add 1.5k diamond"));
                                                return 0;
                                            }
                                            Rule r = new Rule();
                                            r.item = StringArgumentType.getString(ctx, "item").trim();
                                            r.minPrice = price;
                                            config.rules.add(r); save(); alerted.clear();
                                            ctx.getSource().sendFeedback(msg("§fGespeichert: §e" + r.item + " §fab §a$" + fmt(price)));
                                            return 1;
                                        }))))));
    }

    /** Neue Regel aus dem Menue: Item aus der Auswahl + Mindestpreis. */
    public static void addRule(ItemStack stack, double minPrice) {
        Rule r = new Rule();
        r.item = stack.getHoverName().getString();
        r.id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        r.plain = stack.getComponentsPatch().isEmpty();
        r.minPrice = minPrice;
        config.rules.add(r);
        save();
        alerted.clear();
        nextBackground = 0;   // gleich pruefen
    }

    public static void removeRule(int index) {
        if (index >= 0 && index < config.rules.size()) config.rules.remove(index);
        save();
        alerted.clear();
    }

    private static void list(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource src) {
        src.sendFeedback(msg("§6§lOrderAlert §7(" + (config.enabled ? "§aan" : "§caus") + "§7)"));
        if (config.rules.isEmpty()) {
            src.sendFeedback(msg("§7Keine Regeln. §f/orderalert add <Preis> <Item>"));
            return;
        }
        for (int i = 0; i < config.rules.size(); i++) {
            Rule r = config.rules.get(i);
            src.sendFeedback(msg("§7" + (i + 1) + ". §e" + r.item + " §fab §a$" + fmt(r.minPrice)));
        }
    }

    private static Component msg(String s) {
        return Component.literal(s);
    }

    // ---------------------------------------------------------------- Menue lesen

    private static void onTick(Minecraft mc) {
        if (!config.enabled || mc.player == null || config.rules.isEmpty()) return;
        long now = System.currentTimeMillis();
        alerted.values().removeIf(t -> now - t > 30 * 60_000L);

        // 1) Menue ist offen: direkt mitlesen
        if (mc.screen instanceof AbstractContainerScreen<?> screen) {
            if (++tick % 10 != 0) return;
            List<ItemStack> items = new ArrayList<>();
            for (Slot slot : screen.getMenu().slots) {
                if (slot.container != mc.player.getInventory()) items.add(slot.getItem());
            }
            if (isOrderMenu(screen.getTitle().getString(), items)) scan(mc, items);
            nextBackground = now + config.intervalSeconds * 1000L;   // gerade selbst drin
            return;
        }

        // Verstecktes Menue auswerten, sobald die Orders nachgeladen sind:
        // 0,8 s nach dem letzten Paket (mindestens 1,5 s nach dem Oeffnen), spaetestens nach 4 s
        if (hiddenContainer != -1) {
            long sinceOpen = now - hiddenSince, quiet = now - hiddenUpdated;
            if ((sinceOpen > 1500L && quiet > 800L) || sinceOpen > 4000L) finishHidden(mc);
        }
        if (verbose && now > verboseUntil) {
            verbose = false;
            if (!verboseAnswered) say(mc, "\u00a7cAuf /" + config.command + " kam kein Men\u00fc zur\u00fcck. Ist der Befehl richtig? (config/orderalert.json)");
        }

        // 2) Hintergrund: regelmaessig /order oeffnen lassen, aber nie, wenn ein Menue/Chat offen ist
        if (!config.backgroundCheck || mc.screen != null || mc.getConnection() == null) return;
        if (now < nextBackground || hiddenContainer != -1) return;
        requestNow(mc);
    }

    private static void finishHidden(Minecraft mc) {
        int id = hiddenContainer;
        hiddenContainer = -1;
        List<ItemStack> items = hiddenItems;
        hiddenItems = new ArrayList<>();
        int top = Math.max(0, items.size() - 36);
        scan(mc, new ArrayList<>(items.subList(0, top)));
        if (mc.getConnection() != null) mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundContainerClosePacket(id));
    }

    /** /order im Hintergrund schicken. */
    public static void requestNow(Minecraft mc) {
        long now = System.currentTimeMillis();
        nextBackground = now + Math.max(5, config.intervalSeconds) * 1000L;
        ownRequestUntil = now + 4000L;
        if (mc.getConnection() != null) mc.getConnection().sendCommand(config.command);
    }

    /** Knopf "Jetzt pruefen": eine Pruefung mit ausfuehrlicher Ausgabe. */
    public static void testNow(Minecraft mc) {
        verbose = true;
        verboseAnswered = false;
        verboseUntil = System.currentTimeMillis() + 5000L;
        alerted.clear();
        requestNow(mc);
    }

    private static void say(Minecraft mc, String text) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(text), false);
    }

    /** Ist das ein Order-Menue? Titel ODER mindestens ein Item mit "Preis pro". */
    public static boolean isOrderMenu(String title, List<ItemStack> items) {
        String t = title.toLowerCase(Locale.ROOT);
        for (String w : config.titleContains) if (t.contains(w.toLowerCase(Locale.ROOT))) return true;
        for (ItemStack st : items) {
            for (String line : lore(st)) if (line.toLowerCase(Locale.ROOT).contains("preis pro") || line.toLowerCase(Locale.ROOT).contains("price per")) return true;
        }
        return false;
    }

    private static List<String> lore(ItemStack st) {
        List<String> out = new ArrayList<>();
        if (st == null || st.isEmpty()) return out;
        ItemLore il = st.get(DataComponents.LORE);
        if (il != null) for (Component c : il.lines()) out.add(c.getString());
        return out;
    }

    private static final Pattern DELIVERED = Pattern.compile("([\\d.,]+)\\s*/\\s*([\\d.,]+)\\s*geliefert", Pattern.CASE_INSENSITIVE);

    /** Alle Order-Items pruefen und bei Treffern melden. */
    public static void scan(Minecraft mc, List<ItemStack> items) {
        if (mc.player == null) return;
        boolean v = verbose;
        if (v) {
            verboseAnswered = true;
            verbose = false;
            int n = 0;
            for (ItemStack st : items) if (st != null && !st.isEmpty()) n++;
            say(mc, "\u00a76\u00a7lOrderAlert-Test: \u00a7f" + n + " Items im Men\u00fc gelesen");
        }
        int orders = 0, hits = 0;
        for (ItemStack st : items) {
            if (st == null || st.isEmpty()) continue;
            String name = st.getHoverName().getString();
            String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            List<String> lore = lore(st);
            if (!isOrder(lore)) continue;   // Knoepfe usw. ueberspringen
            double price = findPrice(lore);
            if (price < 0) continue;
            orders++;
            if (v && orders <= 8) say(mc, "\u00a77- \u00a7e" + itemLine(lore, name) + " \u00a77f\u00fcr \u00a7a$" + fmt(price));
            // Schon voll geliefert? Dann uninteressant.
            boolean full = false;
            for (String line : lore) {
                Matcher d = DELIVERED.matcher(line);
                if (d.find() && parseNumber(d.group(1)) >= parseNumber(d.group(2))) full = true;
            }
            if (full) continue;
            String what = itemLine(lore, name);
            String fullId = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
            for (Rule r : config.rules) {
                if (price < r.minPrice) continue;
                boolean hit;
                if (r.id != null && !r.id.isEmpty()) {
                    // Aus dem Menue: genauer Name, oder bei Items ohne Varianten die ID
                    hit = what.equalsIgnoreCase(r.item.trim()) || (r.plain && fullId.equals(r.id));
                } else {
                    hit = matches(r.item, what + " " + name + " " + String.join(" ", lore), id);
                }
                if (!hit) continue;
                String key = String.join("|", lore.size() > 3 ? lore.subList(0, 4) : lore) + "|" + name;
                hits++;
                if (!alerted.containsKey(key)) {
                    alerted.put(key, System.currentTimeMillis());
                    notify(mc, what, price);
                }
            }
        }
        if (v) say(mc, "\u00a7f" + orders + " Orders erkannt, \u00a7a" + hits + " \u00a7fpassen zu deinen Alarmen.");
    }

    /** Eine Order hat eine Zeile "Preis pro Stueck" (HugoSMP). */
    private static boolean isOrder(List<String> lore) {
        for (String l : lore) {
            String x = l.toLowerCase(Locale.ROOT);
            if (x.contains("preis pro") || x.contains("price per")) return true;
        }
        return false;
    }

    /** Der Item-Name steht bei HugoSMP in der Zeile vor "Preis pro Stueck". */
    private static String itemLine(List<String> lore, String fallback) {
        for (int i = 1; i < lore.size(); i++) {
            String l = lore.get(i).toLowerCase(Locale.ROOT);
            if (l.contains("preis pro") || l.contains("price per")) return lore.get(i - 1).trim();
        }
        return fallback;
    }

    private static boolean matches(String want, String text, String id) {
        String w = want.toLowerCase(Locale.ROOT).trim();
        String n = text.toLowerCase(Locale.ROOT);
        return n.contains(w) || id.equals(w.replace(' ', '_')) || id.contains(w.replace(' ', '_'));
    }

    private static void notify(Minecraft mc, String item, double price) {
        String text = "§6§lNeue Order! §e" + item + " §ffuer §a$" + fmt(price) + " §fpro Stueck §7- /" + config.command;
        mc.player.displayClientMessage(Component.literal(text), false);
        SystemToast.addOrUpdate(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Order gefunden"), Component.literal(item + " - $" + fmt(price)));
        playAlarm(mc);
    }

    /** Lauter Enderdrachen-Sound ueber "Master" - kommt auch, wenn andere Lautstaerken leise sind. */
    public static void playAlarm(Minecraft mc) {
        mc.getSoundManager().play(new SimpleSoundInstance(SoundEvents.ENDER_DRAGON_GROWL.location(),
                net.minecraft.sounds.SoundSource.MASTER, 1.0F, 1.0F,
                net.minecraft.client.resources.sounds.SoundInstance.createUnseededRandom(), false, 0,
                net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true));
    }

    // ---------------------------------------------------------------- Preis lesen

    /** Erste Zahl in der ersten Zeile, die nach Preis aussieht. -1 = keiner gefunden. */
    static double findPrice(List<String> lore) {
        for (String line : lore) {
            String l = line.toLowerCase(Locale.ROOT);
            boolean priceLine = false;
            for (String k : config.priceLineContains) if (l.contains(k.toLowerCase(Locale.ROOT))) priceLine = true;
            if (!priceLine) continue;
            Matcher m = NUMBER.matcher(line);
            if (m.find()) {
                double v = parseNumber(m.group(1) + m.group(2));
                if (v >= 0) return v;
            }
        }
        return -1;
    }

    /** "1.5k" -> 1500, "1.234,5" -> 1234.5, "2,5m" -> 2500000 */
    static double parseNumber(String s) {
        s = s.trim().replace("$", "").replace(" ", "");
        if (s.isEmpty()) return -1;
        double mult = 1;
        char last = Character.toLowerCase(s.charAt(s.length() - 1));
        if (last == 'k') mult = 1e3; else if (last == 'm') mult = 1e6; else if (last == 'b') mult = 1e9; else if (last == 't') mult = 1e12;
        if (mult != 1) s = s.substring(0, s.length() - 1);
        int dot = s.lastIndexOf('.'), comma = s.lastIndexOf(',');
        if (dot >= 0 && comma >= 0) {
            if (comma > dot) s = s.replace(".", "").replace(',', '.');
            else s = s.replace(",", "");
        } else if (comma >= 0) {
            s = (s.length() - comma - 1 == 3 && mult == 1) ? s.replace(",", "") : s.replace(',', '.');
        } else if (dot >= 0 && s.indexOf('.') != dot) {
            s = s.replace(".", "");                       // 1.234.567
        } else if (dot >= 0 && s.length() - dot - 1 == 3 && mult == 1) {
            s = s.replace(".", "");                       // 1.234 = tausend
        }
        try {
            return Double.parseDouble(s) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String fmt(double v) {
        if (v >= 1e9) return trim(v / 1e9) + "B";
        if (v >= 1e6) return trim(v / 1e6) + "M";
        if (v >= 1e3) return trim(v / 1e3) + "K";
        return trim(v);
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
