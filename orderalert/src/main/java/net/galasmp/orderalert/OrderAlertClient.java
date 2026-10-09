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
        public String item;
        public double minPrice;
    }

    public static final class Config {
        public boolean enabled = true;
        /** Das Menue gilt als Order-Menue, wenn sein Titel eines dieser Woerter enthaelt */
        public List<String> titleContains = new ArrayList<>(List.of("order", "auftr"));
        /** Lore-Zeilen mit diesen Woertern enthalten den Preis */
        public List<String> priceLineContains = new ArrayList<>(List.of("$", "preis", "price", "pro ", "each", "per ", "zahlt", "pays", "belohnung", "reward"));
        public List<Rule> rules = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d.,]*)\\s*([kKmMbBtT]?)");
    private static Config config = new Config();
    private static final Set<String> alerted = new HashSet<>();
    private static int tick;

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
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // nicht schlimm
        }
    }

    @Override
    public void onInitializeClient() {
        load();
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
        if (!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            alerted.clear();   // naechstes Oeffnen meldet wieder
            return;
        }
        if (++tick % 10 != 0) return;
        String title = screen.getTitle().getString().toLowerCase(Locale.ROOT);
        boolean isOrders = false;
        for (String t : config.titleContains) if (title.contains(t.toLowerCase(Locale.ROOT))) isOrders = true;
        if (!isOrders) return;

        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack st = slot.getItem();
            if (st.isEmpty()) continue;
            String name = st.getHoverName().getString();
            String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            List<String> lore = new ArrayList<>();
            ItemLore il = st.get(DataComponents.LORE);
            if (il != null) for (Component c : il.lines()) lore.add(c.getString());
            double price = findPrice(lore);
            if (price < 0) continue;
            for (Rule r : config.rules) {
                if (!matches(r.item, name, id) || price < r.minPrice) continue;
                String key = name + "|" + price + "|" + String.join("|", lore);
                if (alerted.add(key)) notify(mc, name, price);
            }
        }
    }

    private static boolean matches(String want, String name, String id) {
        String w = want.toLowerCase(Locale.ROOT).trim();
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains(w) || id.equals(w.replace(' ', '_')) || id.contains(w.replace(' ', '_'));
    }

    private static void notify(Minecraft mc, String item, double price) {
        String text = "§6§lOrder! §e" + item + " §ffuer §a$" + fmt(price);
        mc.player.displayClientMessage(Component.literal(text), false);
        SystemToast.addOrUpdate(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Order gefunden"), Component.literal(item + " - $" + fmt(price)));
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.4F));
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
