package dev.fce;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FORJA DE FUSION — fusiona dos libros identicos arrastrando uno sobre otro.
 *
 * Mecanica (drag & drop, igual que aplicar un libro a un item):
 *  - Libro FCE en el cursor + clic izquierdo sobre OTRO libro FCE del MISMO
 *    encantamiento y MISMO nivel, en el inventario propio del jugador.
 *  - Tirada 1-100 contra la probabilidad de fusion del tier (modules/fusion.yml)
 *    mas el DESTINO DE FORJA acumulado (pity): cada fallo consecutivo del mismo
 *    tier suma puntos al siguiente intento. La racha se PERSISTE en stats.yml
 *    (via PlayerStats), asi que sobrevive relogs y reinicios del servidor.
 *
 * Resultado con exito (nivel < maximo):
 *  - Libro de nivel +1.
 *  - Exito del libro: hereda el MEJOR % de los dos, mas success-bonus.
 *  - Ruptura: la MENOR de las dos.
 *
 * FUSION PERFECTA (ambos libros al nivel maximo):
 *  - Sin tirada: exito garantizado. El libro resultante conserva el nivel
 *    maximo y gana perfect.success-bonus puntos de exito (hasta el techo
 *    limits.max-success-rate del config principal). Da uso a los duplicados
 *    de nivel maximo, que antes eran peso muerto.
 *
 * Si la fusion FALLA:
 *  - Solo se consume el libro del cursor (el sacrificado); el otro sobrevive.
 *  - Se acumula destino de forja para el proximo intento del mismo tier.
 *  - Compensacion opcional en polvo (failure.compensation-dust).
 *
 * SEGURIDAD (mismas reglas que DragAndDropListener):
 *  - Solo inventario inferior del propio jugador: nunca sobre GUIs.
 *  - Prioridad NORMAL con ignoreCancelled=true: la capa antidupe (LOW)
 *    evalua antes y puede cancelar; DragAndDropListener (HIGH) ve el evento
 *    ya cancelado y no interfiere.
 *  - Un libro sin % de exito almacenado se trata como corrupto y se rechaza
 *    sin consumir nada.
 *
 * Registro (FabledCustomEnchantsPlugin.onEnable()):
 *     fusion = new FusionListener(this); fusion.load();
 *     getServer().getPluginManager().registerEvents(fusion, this);
 */
public final class FusionListener implements Listener {

    private final FabledCustomEnchantsPlugin plugin;

    // Configuracion (modules/fusion.yml)
    private boolean enabled;
    private final Map<String, Integer> chanceByTier = new HashMap<>();
    private int defaultChance;
    private int pityPerFail;
    private int successBonus;
    private boolean perfectEnabled;
    private int perfectBonus;
    private String compensationDustId;
    private int compensationAmount;
    private boolean broadcastEnabled;
    private final Set<String> broadcastTiers = new HashSet<>();
    private String broadcastMessage;

    public FusionListener(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)carga modules/fusion.yml. Llamar tambien desde /fce reload. */
    public void load() {
        File file = new File(plugin.getDataFolder(), "modules/fusion.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        enabled = cfg.getBoolean("fusion.enabled", true);
        defaultChance = clamp(cfg.getInt("fusion.default-chance", 75));
        pityPerFail = Math.max(0, cfg.getInt("fusion.pity-per-fail", 6));
        successBonus = Math.max(0, cfg.getInt("fusion.success-bonus", 5));
        perfectEnabled = cfg.getBoolean("fusion.perfect.enabled", true);
        perfectBonus = Math.max(1, cfg.getInt("fusion.perfect.success-bonus", 10));
        compensationDustId = cfg.getString("fusion.failure.compensation-dust", "");
        compensationAmount = Math.max(0, cfg.getInt("fusion.failure.compensation-amount", 1));
        broadcastEnabled = cfg.getBoolean("fusion.broadcast.enabled", true);
        broadcastMessage = cfg.getString("fusion.broadcast.message",
                "<gradient:#FFB703:#FB8500>⚒ {player} ha forjado</gradient> <white>{enchant} {nivel}</white> <gradient:#FB8500:#FFB703>en la Forja de Fusión!</gradient>");

        broadcastTiers.clear();
        for (String t : cfg.getStringList("fusion.broadcast.tiers")) {
            broadcastTiers.add(t.toLowerCase(Locale.ROOT));
        }

        chanceByTier.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("fusion.chance-by-tier");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                chanceByTier.put(key.toLowerCase(Locale.ROOT), clamp(sec.getInt(key)));
            }
        }
    }

    /* ==================== Evento principal ==================== */

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!enabled) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClick() != ClickType.LEFT) return;

        // Solo inventario inferior del PROPIO jugador, nunca GUIs.
        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getBottomInventory()) {
            return;
        }

        ItemStack cursor = event.getCursor();
        ItemStack target = event.getCurrentItem();
        if (!isFceBook(cursor) || !isFceBook(target)) return;

        PersistentDataContainer a = cursor.getItemMeta().getPersistentDataContainer();
        PersistentDataContainer b = target.getItemMeta().getPersistentDataContainer();
        String idA = a.get(Keys.ENCHANT_ID, PersistentDataType.STRING);
        String idB = b.get(Keys.ENCHANT_ID, PersistentDataType.STRING);
        if (idA == null || idB == null) return;

        // A partir de aqui la interaccion pertenece a la forja.
        event.setCancelled(true);

        if (!idA.equalsIgnoreCase(idB)) {
            plugin.messages().playSound(player, "purchase-denied");
            plugin.messages().sendRaw(player,
                    "<red>Solo puedes fusionar dos libros del mismo encantamiento.");
            return;
        }

        EnchantDefinition def = plugin.enchants().get(idA);
        TierRegistry.Tier tier = def == null ? null : plugin.tiers().get(def.tierId());
        if (def == null || tier == null) return;

        Integer sa = a.get(Keys.SUCCESS, PersistentDataType.INTEGER);
        Integer sb = b.get(Keys.SUCCESS, PersistentDataType.INTEGER);
        if (sa == null || sb == null) {
            plugin.messages().playSound(player, "purchase-denied");
            plugin.messages().sendRaw(player,
                    "<red>Uno de los libros está corrupto y no puede fusionarse.");
            return;
        }

        int la = a.getOrDefault(Keys.ENCHANT_LEVEL, PersistentDataType.INTEGER, 1);
        int lb = b.getOrDefault(Keys.ENCHANT_LEVEL, PersistentDataType.INTEGER, 1);
        int da = a.getOrDefault(Keys.DESTROY, PersistentDataType.INTEGER, 0);
        int db = b.getOrDefault(Keys.DESTROY, PersistentDataType.INTEGER, 0);
        int cap = Math.min(100, plugin.getConfig().getInt("limits.max-success-rate", 100));

        // --- FUSION PERFECTA: ambos al nivel maximo -> mejora garantizada ---
        if (la >= def.maxLevel() && lb >= def.maxLevel()) {
            if (!perfectEnabled) {
                plugin.messages().sendRaw(player,
                        "<gray>Estos libros ya están al nivel máximo.");
                return;
            }
            int best = Math.max(sa, sb);
            if (best >= cap) {
                plugin.messages().playSound(player, "purchase-denied");
                plugin.messages().sendRaw(player,
                        "<gray>Este libro ya alcanzó el éxito máximo (<white>" + cap + "%</white>).");
                return;
            }
            int newSuccess = Math.min(cap, best + perfectBonus);
            ItemStack fused = plugin.books().create(def, tier, def.maxLevel(), newSuccess, Math.min(da, db));
            consumeAndGive(event, player, cursor, target, fused);

            plugin.messages().playSound(player, "apply-success");
            player.getWorld().spawnParticle(Particle.ENCHANTED_HIT,
                    player.getLocation().add(0, 1, 0), 60, 0.4, 0.6, 0.4, 0.2);
            player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                    player.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, 0.1);
            plugin.messages().sendRaw(player,
                    "<gold>⚒ ¡Fusión Perfecta!</gold> <white>" + def.displayName() + " "
                            + BookFactory.roman(def.maxLevel()) + "</white> <gray>· Éxito <green>"
                            + newSuccess + "%</green> · Ruptura <red>" + Math.min(da, db) + "%</red>");
            return;
        }

        // --- Fusion de nivel: requiere el mismo nivel en ambos libros ---
        if (la != lb) {
            plugin.messages().playSound(player, "purchase-denied");
            plugin.messages().sendRaw(player,
                    "<red>Ambos libros deben tener el mismo nivel para fusionarse.");
            return;
        }

        int chance = chanceByTier.getOrDefault(tier.id().toLowerCase(Locale.ROOT), defaultChance);
        int bonus = pityBonus(player, tier.id());
        int effective = Math.min(100, chance + bonus);

        if (bonus > 0) {
            plugin.messages().sendRaw(player,
                    "<gray>Destino de forja: <green>+" + bonus + "%</green> <dark_gray>("
                            + chance + "% → " + effective + "%)");
        }

        int roll = ThreadLocalRandom.current().nextInt(1, 101);

        if (roll <= effective) {
            resetFails(player, tier.id());
            int newLevel = la + 1;
            int newSuccess = Math.min(cap, Math.max(sa, sb) + successBonus);
            int newDestroy = Math.max(0, Math.min(da, db));
            ItemStack fused = plugin.books().create(def, tier, newLevel, newSuccess, newDestroy);
            consumeAndGive(event, player, cursor, target, fused);

            plugin.messages().playSound(player, "apply-success");
            player.getWorld().spawnParticle(Particle.ENCHANTED_HIT,
                    player.getLocation().add(0, 1, 0), 60, 0.4, 0.6, 0.4, 0.2);
            plugin.messages().sendRaw(player,
                    "<green>⚒ ¡Fusión exitosa!</green> <white>" + def.displayName() + " "
                            + BookFactory.roman(newLevel) + "</white> <gray>· Éxito <green>"
                            + newSuccess + "%</green> · Ruptura <red>" + newDestroy + "%</red>");

            if (broadcastEnabled && broadcastTiers.contains(tier.id().toLowerCase(Locale.ROOT))) {
                String msg = broadcastMessage
                        .replace("{player}", player.getName())
                        .replace("{enchant}", def.displayName())
                        .replace("{nivel}", BookFactory.roman(newLevel))
                        .replace("{exito}", String.valueOf(newSuccess));
                for (Player online : Bukkit.getOnlinePlayers()) {
                    plugin.messages().sendRaw(online, msg);
                }
            }
        } else {
            int streak = addFail(player, tier.id());
            consumeCursorOne(event, cursor);

            plugin.messages().playSound(player, "purchase-denied");
            plugin.messages().sendRaw(player,
                    "<red>⚒ La fusión ha fallado.</red> <gray>El libro sacrificado se consumió; el otro sobrevive.");
            plugin.messages().sendRaw(player,
                    "<gray>Racha de forja: <white>" + streak + "</white> <dark_gray>· <gray>próximo intento <green>+"
                            + pityBonus(player, tier.id()) + "%</green>");

            giveCompensation(player);
        }
    }

    /* ==================== Ayudas ==================== */

    private boolean isFceBook(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(Keys.ENCHANT_ID, PersistentDataType.STRING);
    }

    /** Consume 1 del cursor y 1 del objetivo, y entrega el libro fusionado. */
    private void consumeAndGive(InventoryClickEvent event, Player player,
                                ItemStack cursor, ItemStack target, ItemStack fused) {
        consumeCursorOne(event, cursor);
        if (target.getAmount() <= 1) {
            event.setCurrentItem(fused);
        } else {
            target.setAmount(target.getAmount() - 1);
            giveOrDrop(player, fused);
        }
    }

    /** Gasta una unidad del stack que lleva el cursor. */
    private void consumeCursorOne(InventoryClickEvent event, ItemStack cursor) {
        int amount = cursor.getAmount();
        if (amount <= 1) {
            event.getView().setCursor(null);
        } else {
            cursor.setAmount(amount - 1);
            event.getView().setCursor(cursor);
        }
    }

    private void giveOrDrop(Player player, ItemStack item) {
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
    }

    private void giveCompensation(Player player) {
        if (compensationDustId == null || compensationDustId.isBlank() || compensationAmount <= 0) return;
        DustRegistry.Dust dust = plugin.dusts().get(compensationDustId);
        if (dust == null) return;
        giveOrDrop(player, plugin.dusts().create(dust, compensationAmount));
        plugin.messages().sendRaw(player,
                "<gray>Recuperas <white>" + dust.displayName() + " x" + compensationAmount
                        + "</white> de los restos de la fusión.");
    }

    /** Lineas para /fce fusion: reglas y probabilidades, con el pity personal. */
    public List<String> describe(Player player) {
        List<String> out = new ArrayList<>();
        if (!enabled) {
            out.add("<gray>La Forja de Fusión está desactivada.");
            return out;
        }
        out.add("<gray>Arrastra un libro sobre otro <white>idéntico</white> (mismo encanto y nivel) en tu inventario.");
        out.add("<gray>Resultado: nivel <white>+1</white> · hereda el mejor éxito <green>+" + successBonus
                + "%</green> · conserva la menor ruptura.");
        for (TierRegistry.Tier t : plugin.tiers().all()) {
            int chance = chanceByTier.getOrDefault(t.id().toLowerCase(Locale.ROOT), defaultChance);
            int bonus = pityBonus(player, t.id());
            String line = "<dark_gray>│ <white>" + capitalize(t.id()) + "</white> <gray>· " + chance + "%";
            if (bonus > 0) line += " <green>(+" + bonus + "% destino de forja)</green>";
            out.add(line);
        }
        if (perfectEnabled) {
            out.add("<gray>Dos libros al nivel máximo → <gold>Fusión Perfecta</gold>: <green>+"
                    + perfectBonus + "%</green> de éxito, sin riesgo.");
        }
        out.add("<gray>Si falla, solo pierdes el libro sacrificado y acumulas <green>+" + pityPerFail
                + "%</green> por fallo para el próximo intento.");
        return out;
    }

    /* ==================== Destino de forja (pity) ==================== */
    // Persistido en stats.yml via PlayerStats: la racha sobrevive relogs,
    // reinicios y crashes del servidor (antes vivia solo en memoria y se
    // perdia justo cuando el jugador mas invertido estaba).

    private int addFail(Player player, String tierId) {
        return plugin.stats().onFusionFail(player, tierId);
    }

    private void resetFails(Player player, String tierId) {
        plugin.stats().resetFusionStreak(player, tierId);
    }

    private int pityBonus(Player player, String tierId) {
        return plugin.stats().fusionStreak(player.getUniqueId(), tierId) * pityPerFail;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
