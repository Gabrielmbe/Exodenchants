package dev.fce;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * TRITURADORA — recicla libros de encantamiento en polvos y esencias.
 *
 * Mecanica (drag & drop, coherente con el resto del plugin):
 *  - Libro FCE en el cursor + clic izquierdo sobre una ESTACION (por defecto
 *    una PIEDRA DE AFILAR / GRINDSTONE como item) en el inventario propio.
 *  - El libro se consume y devuelve polvo segun su tier (modules/recycle.yml).
 *  - El rendimiento escala con el NIVEL del libro (multiply-by-level).
 *  - GOLPE DE SUERTE: probabilidad configurable de obtener ademas una esencia
 *    (bonus-dust) — el momento jackpot que hace divertido triturar. Se ANUNCIA
 *    a todo el servidor (announce.on-jackpot): lo que suena en el chat, la
 *    gente lo persigue.
 *
 * Cierra el loop economico del plugin:
 *    libro malo -> triturar -> polvo -> mejorar libro bueno -> fusionar.
 *  Ningun drop queda muerto: hasta el peor libro alimenta el progreso.
 *
 * NOTA: los libros "corruptos" (sin % de exito almacenado, tipicamente de
 * versiones viejas) SI pueden triturarse: es la via de escape para que esos
 * items legados no queden inservibles tras el endurecimiento de seguridad.
 *
 * SEGURIDAD (mismas reglas que Fusion y DragAndDrop):
 *  - Solo inventario inferior del propio jugador: nunca sobre GUIs.
 *  - Prioridad NORMAL con ignoreCancelled=true: el antidupe (LOW) evalua
 *    antes; DragAndDropListener (HIGH) ve el evento ya cancelado.
 *  - La estacion NO se consume, solo el libro.
 *
 * Registro (FabledCustomEnchantsPlugin.onEnable()):
 *     recycler = new RecycleListener(this); recycler.load();
 *     getServer().getPluginManager().registerEvents(recycler, this);
 */
public final class RecycleListener implements Listener {

    /** Rendimiento de un tier: polvo base + posible bonus (esencia). */
    private record Yield(String dustId, int amount, String bonusDustId, int bonusChance) {}

    private final FabledCustomEnchantsPlugin plugin;

    private boolean enabled;
    private Material station;
    private boolean multiplyByLevel;
    private Yield defaultYield;
    private final Map<String, Yield> yieldByTier = new HashMap<>();

    public RecycleListener(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    /** (Re)carga modules/recycle.yml. Llamar tambien desde /fce reload. */
    public void load() {
        File file = new File(plugin.getDataFolder(), "modules/recycle.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        enabled = cfg.getBoolean("recycle.enabled", true);
        multiplyByLevel = cfg.getBoolean("recycle.multiply-by-level", true);

        Material mat = Material.matchMaterial(cfg.getString("recycle.station", "GRINDSTONE"));
        station = mat == null ? Material.GRINDSTONE : mat;

        defaultYield = new Yield(
                cfg.getString("recycle.default.dust", "polvo_menor"),
                Math.max(1, cfg.getInt("recycle.default.amount", 1)),
                cfg.getString("recycle.default.bonus-dust", ""),
                clamp(cfg.getInt("recycle.default.bonus-chance", 0)));

        yieldByTier.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("recycle.by-tier");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection t = sec.getConfigurationSection(key);
                if (t == null) continue;
                yieldByTier.put(key.toLowerCase(Locale.ROOT), new Yield(
                        t.getString("dust", defaultYield.dustId()),
                        Math.max(1, t.getInt("amount", defaultYield.amount())),
                        t.getString("bonus-dust", ""),
                        clamp(t.getInt("bonus-chance", 0))));
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
        if (!isFceBook(cursor)) return;
        if (target == null || target.getType() != station) return;

        // A partir de aqui la interaccion pertenece a la trituradora.
        event.setCancelled(true);

        PersistentDataContainer data = cursor.getItemMeta().getPersistentDataContainer();
        String enchantId = data.get(Keys.ENCHANT_ID, PersistentDataType.STRING);
        int level = data.getOrDefault(Keys.ENCHANT_LEVEL, PersistentDataType.INTEGER, 1);

        // El tier decide el rendimiento; un encantamiento desconocido (libro de
        // una version vieja) usa el rendimiento por defecto en vez de fallar.
        EnchantDefinition def = enchantId == null ? null : plugin.enchants().get(enchantId);
        String tierId = def == null ? "" : def.tierId().toLowerCase(Locale.ROOT);
        Yield yield = yieldByTier.getOrDefault(tierId, defaultYield);

        DustRegistry.Dust dust = plugin.dusts().get(yield.dustId());
        if (dust == null) {
            plugin.getLogger().warning("recycle.yml: polvo desconocido '" + yield.dustId()
                    + "' para el tier '" + tierId + "'.");
            return;
        }

        int amount = yield.amount() * (multiplyByLevel ? Math.max(1, level) : 1);
        amount = Math.max(1, Math.min(64, amount));

        // Consumir el libro y entregar el polvo.
        consumeCursorOne(event, cursor);
        giveOrDrop(player, plugin.dusts().create(dust, amount));

        plugin.messages().playSound(player, "dust-applied");
        player.getWorld().spawnParticle(Particle.ENCHANTED_HIT,
                player.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.1);

        String name = def == null ? "Libro antiguo" : def.displayName() + " " + BookFactory.roman(level);
        plugin.messages().sendRaw(player,
                "<gray>⚒ Trituraste <white>" + name + "</white> <dark_gray>→ <white>"
                        + dust.displayName() + " x" + amount + "</white>");

        // GOLPE DE SUERTE: esencia extra con probabilidad configurable.
        String bonusId = yield.bonusDustId();
        if (bonusId != null && !bonusId.isBlank() && yield.bonusChance() > 0
                && ThreadLocalRandom.current().nextInt(1, 101) <= yield.bonusChance()) {
            DustRegistry.Dust bonus = plugin.dusts().get(bonusId);
            if (bonus != null) {
                giveOrDrop(player, plugin.dusts().create(bonus, 1));
                plugin.messages().playSound(player, "apply-success");
                player.playSound(player.getLocation(), "entity.player.levelup", 1.0f, 1.4f);
                player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                        player.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, 0.1);
                plugin.messages().sendRaw(player,
                        "<gold>✨ ¡Golpe de suerte!</gold> <gray>Entre los restos encuentras <white>"
                                + bonus.displayName() + "</white>.");
                // Los momentos raros son ruidosos: el servidor entero se entera.
                plugin.announcer().jackpot(player, bonus.displayName());
            }
        }
    }

    /* ==================== Ayudas ==================== */

    private boolean isFceBook(ItemStack item) {
        if (item == null || item.getType() != Material.ENCHANTED_BOOK || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(Keys.ENCHANT_ID, PersistentDataType.STRING);
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

    /** Lineas para /fce reciclar: reglas y rendimiento por tier. */
    public List<String> describe(Player player) {
        List<String> out = new ArrayList<>();
        if (!enabled) {
            out.add("<gray>La trituradora está desactivada.");
            return out;
        }
        out.add("<gray>Arrastra un libro sobre una <white>" + stationName()
                + "</white> de tu inventario para triturarlo en polvo.");
        if (multiplyByLevel) {
            out.add("<gray>El rendimiento se multiplica por el <white>nivel</white> del libro.");
        }
        for (TierRegistry.Tier t : plugin.tiers().all()) {
            Yield y = yieldByTier.getOrDefault(t.id().toLowerCase(Locale.ROOT), defaultYield);
            DustRegistry.Dust dust = plugin.dusts().get(y.dustId());
            if (dust == null) continue;
            String line = "<dark_gray>│ <white>" + capitalize(t.id()) + "</white> <gray>→ "
                    + dust.displayName() + " x" + y.amount();
            DustRegistry.Dust bonus = plugin.dusts().get(y.bonusDustId() == null ? "" : y.bonusDustId());
            if (bonus != null && y.bonusChance() > 0) {
                line += " <dark_gray>· <gray>" + y.bonusChance() + "% de " + bonus.displayName();
            }
            out.add(line);
        }
        out.add("<gray>Los libros antiguos o corruptos también pueden triturarse.");
        return out;
    }

    private String stationName() {
        return station.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
