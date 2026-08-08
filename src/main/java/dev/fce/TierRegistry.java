package dev.fce;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tiers y sorteo de porcentajes (pools/tiers.yml).
 */
public class TierRegistry {

    public record Tier(String id, String display, String color, int weight,
                       int successMin, int successMax,
                       int destroyMin, int destroyMax,
                       int maxBookLevel, boolean glint, double price) {
    }

    private final Map<String, Tier> tiers = new LinkedHashMap<>();
    private int roundTo = 5;
    private boolean clampTo100 = true;

    public void load(JavaPlugin plugin) {
        tiers.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "pools/tiers.yml"));
        ConfigurationSection sec = yml.getConfigurationSection("tiers");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection t = sec.getConfigurationSection(id);
                if (t == null) continue;
                String key = id.toLowerCase(Locale.ROOT);
                tiers.put(key, new Tier(
                        key,
                        t.getString("display", id),
                        t.getString("color", "#FFFFFF"),
                        t.getInt("weight", 1),
                        t.getInt("success.min", 50), t.getInt("success.max", 100),
                        t.getInt("destroy.min", 0), t.getInt("destroy.max", 0),
                        t.getInt("max-book-level", 1),
                        t.getBoolean("book-glint", false),
                        t.getDouble("price", 0)));
            }
        }
        roundTo = Math.max(1, yml.getInt("rolling.round-to-multiple-of", 5));
        clampTo100 = yml.getBoolean("rolling.clamp-total-to-100", true);
        plugin.getLogger().info("Tiers cargados: " + tiers.size());
    }

    public Tier get(String id) {
        return id == null ? null : tiers.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<Tier> all() {
        return tiers.values();
    }

    /** Sorteo de tier ponderado por weight (loot aleatorio). */
    public Tier rollTier() {
        int total = tiers.values().stream().mapToInt(Tier::weight).sum();
        if (total <= 0) return null;
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (Tier tier : tiers.values()) {
            roll -= tier.weight();
            if (roll < 0) return tier;
        }
        return null;
    }

    public int rollSuccess(Tier tier) {
        return roll(tier.successMin(), tier.successMax());
    }

    public int rollDestroy(Tier tier, int success) {
        int destroy = roll(tier.destroyMin(), tier.destroyMax());
        if (clampTo100 && success + destroy > 100) destroy = 100 - success;
        return Math.max(0, destroy);
    }

    private int roll(int min, int max) {
        int value = ThreadLocalRandom.current().nextInt(min, max + 1);
        int rounded = Math.round(value / (float) roundTo) * roundTo;
        return Math.max(min, Math.min(max, rounded));
    }
}
