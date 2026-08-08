package dev.fce;

import org.bukkit.Bukkit;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * COMBOS DE SET.
 *
 * Cuenta cuantos encantamientos de un mismo tier lleva equipados el jugador
 * (manos + armadura) y, al alcanzar el umbral, concede un bono pasivo definido
 * en modules/set_combos.yml.
 *
 * Los bonos son efectos de pocion renovados periodicamente (no acumulables por
 * error) y se retiran solos en cuanto el jugador deja de cumplir el combo,
 * porque se aplican con una duracion corta que solo se refresca mientras el
 * conjunto siga equipado.
 */
public class SetComboManager implements Listener {

    /** Un combo declarado en el YAML. */
    public record Combo(String tierId, int required, String display,
                        List<Bonus> bonuses, String announce) {
    }

    public record Bonus(PotionEffectType type, int amplifier) {
    }

    private final FabledCustomEnchantsPlugin plugin;
    private final Map<String, Combo> byTier = new LinkedHashMap<>();
    /** Ultimo combo notificado a cada jugador, para no repetir el aviso. */
    private final Map<UUID, String> notified = new HashMap<>();
    private boolean enabled = true;
    private int refreshTicks = 60;

    public SetComboManager(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------
    // CARGA
    // ------------------------------------------------------------
    public void load() {
        byTier.clear();
        File file = new File(plugin.getDataFolder(), "modules/set_combos.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        enabled = yml.getBoolean("enabled", true);
        refreshTicks = Math.max(20, yml.getInt("refresh-ticks", 60));

        ConfigurationSection sets = yml.getConfigurationSection("sets");
        if (sets == null) return;
        for (String tierId : sets.getKeys(false)) {
            ConfigurationSection sec = sets.getConfigurationSection(tierId);
            if (sec == null) continue;

            List<Bonus> bonuses = new ArrayList<>();
            for (Map<?, ?> raw : sec.getMapList("bonuses")) {
                Object typeName = raw.get("potion");
                if (typeName == null) continue;
                PotionEffectType type = potion(String.valueOf(typeName));
                if (type == null) continue;
                int amplifier = 0;
                Object amp = raw.get("amplifier");
                if (amp instanceof Number number) amplifier = number.intValue();
                bonuses.add(new Bonus(type, Math.max(0, amplifier)));
            }
            if (bonuses.isEmpty()) continue;

            byTier.put(tierId.toLowerCase(Locale.ROOT), new Combo(
                    tierId.toLowerCase(Locale.ROOT),
                    Math.max(2, sec.getInt("required", 3)),
                    sec.getString("display", tierId),
                    bonuses,
                    sec.getString("announce", "")));
        }
        plugin.getLogger().info("Combos de set cargados: " + byTier.size());
    }

    /** Bucle de refresco: mantiene vivos los bonos mientras el set siga puesto. */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!enabled) return;
            for (Player online : Bukkit.getOnlinePlayers()) apply(online);
        }, 40L, refreshTicks);
    }

    // ------------------------------------------------------------
    // EVALUACION
    // ------------------------------------------------------------
    /** Encantamientos distintos equipados por tier. */
    public Map<String, Integer> countByTier(Player player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        ItemStack[] equipment = {
                player.getInventory().getItemInMainHand(),
                player.getInventory().getItemInOffHand(),
                player.getInventory().getHelmet(),
                player.getInventory().getChestplate(),
                player.getInventory().getLeggings(),
                player.getInventory().getBoots()
        };
        List<String> seen = new ArrayList<>();
        for (ItemStack item : equipment) {
            if (item == null || item.getType().isAir()) continue;
            for (EnchantDefinition def : plugin.enchants().onItem(item).keySet()) {
                if (seen.contains(def.id())) continue; // no cuenta dos veces el mismo encanto
                seen.add(def.id());
                counts.merge(def.tierId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Combo activo de mayor rango, o null. */
    public Combo activeCombo(Player player) {
        if (!enabled) return null;
        Map<String, Integer> counts = countByTier(player);
        Combo best = null;
        int bestPoints = -1;
        for (Combo combo : byTier.values()) {
            int have = counts.getOrDefault(combo.tierId(), 0);
            if (have < combo.required()) continue;
            int points = plugin.stats().pointsOf(combo.tierId());
            if (points > bestPoints) {
                bestPoints = points;
                best = combo;
            }
        }
        return best;
    }

    /** Aplica (o refresca) los bonos del combo activo. */
    public void apply(Player player) {
        Combo combo = activeCombo(player);
        if (combo == null) {
            notified.remove(player.getUniqueId());
            return;
        }
        // Duracion algo mayor que el refresco: se cae sola si el set se quita
        int ticks = refreshTicks + 40;
        for (Bonus bonus : combo.bonuses()) {
            player.addPotionEffect(new PotionEffect(bonus.type(), ticks,
                    bonus.amplifier(), true, false, false));
        }
        String previous = notified.put(player.getUniqueId(), combo.tierId());
        if (!combo.tierId().equals(previous) && !combo.announce().isBlank()) {
            plugin.messages().sendRaw(player, combo.announce());
            plugin.messages().playSound(player, "combo-activated");
        }
    }

    /** Lineas descriptivas para la GUI y /encantos combos. */
    public List<String> describe(Player player) {
        List<String> lines = new ArrayList<>();
        Map<String, Integer> counts = countByTier(player);
        for (Combo combo : byTier.values()) {
            int have = counts.getOrDefault(combo.tierId(), 0);
            boolean active = have >= combo.required();
            lines.add("<dark_gray>│ " + (active ? "<green>✔ " : "<gray>· ")
                    + combo.display() + " <dark_gray>(<white>" + have + "<gray>/<white>"
                    + combo.required() + "<dark_gray>)");
        }
        return lines;
    }

    public Map<String, Combo> combos() {
        return byTier;
    }

    // ------------------------------------------------------------
    // EVENTOS
    // ------------------------------------------------------------
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> apply(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onHeldChange(PlayerItemHeldEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> apply(event.getPlayer()));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> apply(player));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        notified.remove(event.getPlayer().getUniqueId());
    }

    @SuppressWarnings("deprecation")
    private PotionEffectType potion(String name) {
        String key = name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        PotionEffectType type = PotionEffectType.getByName(key);
        if (type != null) return type;
        return switch (key) {
            case "SPEED", "SWIFTNESS" -> PotionEffectType.SPEED;
            case "HASTE", "FAST_DIGGING" -> PotionEffectType.HASTE;
            case "STRENGTH", "INCREASE_DAMAGE" -> PotionEffectType.STRENGTH;
            case "RESISTANCE", "DAMAGE_RESISTANCE" -> PotionEffectType.RESISTANCE;
            case "REGENERATION", "REGEN" -> PotionEffectType.REGENERATION;
            case "ABSORPTION" -> PotionEffectType.ABSORPTION;
            case "NIGHT_VISION" -> PotionEffectType.NIGHT_VISION;
            case "JUMP_BOOST", "JUMP" -> PotionEffectType.JUMP_BOOST;
            case "FIRE_RESISTANCE" -> PotionEffectType.FIRE_RESISTANCE;
            case "WATER_BREATHING" -> PotionEffectType.WATER_BREATHING;
            case "LUCK" -> PotionEffectType.LUCK;
            default -> null;
        };
    }
}
