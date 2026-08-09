package dev.fce;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Estadisticas persistentes por jugador (stats.yml):
 *
 *   · Encantamientos aplicados con exito, por tier.
 *   · Racha de suerte: fallos consecutivos por tier, que otorgan un bono
 *     acumulativo al % de exito del siguiente intento de ese mismo tier
 *     (sistema anti-mala-suerte).
 *   · Puntuacion para el ranking: cada tier vale unos puntos configurables.
 *
 * El archivo se guarda de forma asincrona con un pequeno retardo agrupado,
 * para no escribir a disco en cada click. Ademas hay un AUTOSAVE periodico
 * (cada 5 min por defecto, configurable en stats.autosave-minutes) para que
 * un crash del servidor no pierda las rachas y rankings desde el arranque.
 */
public class PlayerStats {

    private final FabledCustomEnchantsPlugin plugin;
    private final File file;
    private YamlConfiguration data = new YamlConfiguration();
    private boolean saveScheduled;
    private boolean dirty;

    public PlayerStats(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
    }

    // ------------------------------------------------------------
    // PERSISTENCIA
    // ------------------------------------------------------------
    public void load() {
        if (file.exists()) {
            data = YamlConfiguration.loadConfiguration(file);
        } else {
            data = new YamlConfiguration();
        }
    }

    /**
     * Autosave periodico y asincrono. Antes solo se guardaba en onDisable
     * (mas el retardo agrupado de scheduleSave): si el servidor crasheaba se
     * perdia todo lo acumulado desde el ultimo guardado. Solo escribe si hay
     * cambios pendientes.
     */
    public void startAutosave() {
        long minutes = Math.max(1, plugin.getConfig().getLong("stats.autosave-minutes", 5));
        long ticks = minutes * 60L * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!dirty) return;
            saveNow();
        }, ticks, ticks);
    }

    /** Agrupa las escrituras: varias llamadas seguidas guardan una sola vez. */
    private void scheduleSave() {
        dirty = true;
        if (saveScheduled || !plugin.isEnabled()) return;
        saveScheduled = true;
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            saveScheduled = false;
            saveNow();
        }, 100L);
    }

    public synchronized void saveNow() {
        try {
            if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
            data.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar stats.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------
    // REGISTRO DE RESULTADOS
    // ------------------------------------------------------------
    /** Exito: suma al contador del tier y reinicia su racha de fallos. */
    public void onSuccess(Player player, String tierId) {
        String base = path(player);
        data.set(base + ".name", player.getName());
        data.set(base + ".applied." + tierId, applied(player.getUniqueId(), tierId) + 1);
        data.set(base + ".streak." + tierId, 0);
        data.set(base + ".score", computeScore(player.getUniqueId()));
        scheduleSave();
    }

    /** Fallo: suma a la racha del tier y devuelve la racha resultante. */
    public int onFailure(Player player, String tierId) {
        String base = path(player);
        int streak = streak(player.getUniqueId(), tierId) + 1;
        data.set(base + ".name", player.getName());
        data.set(base + ".streak." + tierId, streak);
        data.set(base + ".fails." + tierId, fails(player.getUniqueId(), tierId) + 1);
        scheduleSave();
        return streak;
    }

    // ------------------------------------------------------------
    // RACHA DE SUERTE
    // ------------------------------------------------------------
    /**
     * Bono al % de exito acumulado por fallos consecutivos en ese tier.
     * bono = fallos x luck.per-fail, con techo luck.max-bonus.
     */
    public int luckBonus(Player player, String tierId) {
        if (!plugin.getConfig().getBoolean("luck.enabled", true)) return 0;
        int perFail = plugin.getConfig().getInt("luck.per-fail", 3);
        int max = plugin.getConfig().getInt("luck.max-bonus", 25);
        return Math.max(0, Math.min(max, streak(player.getUniqueId(), tierId) * perFail));
    }

    public int streak(UUID uuid, String tierId) {
        return data.getInt(uuid + ".streak." + tierId, 0);
    }

    public int applied(UUID uuid, String tierId) {
        return data.getInt(uuid + ".applied." + tierId, 0);
    }

    public int fails(UUID uuid, String tierId) {
        return data.getInt(uuid + ".fails." + tierId, 0);
    }

    public int totalApplied(UUID uuid) {
        int total = 0;
        ConfigurationSection sec = data.getConfigurationSection(uuid + ".applied");
        if (sec == null) return 0;
        for (String key : sec.getKeys(false)) total += sec.getInt(key, 0);
        return total;
    }

    // ------------------------------------------------------------
    // RANKING
    // ------------------------------------------------------------
    /** Puntos por tier: ranking.points.<tier> en config.yml. */
    public int pointsOf(String tierId) {
        return plugin.getConfig().getInt("ranking.points." + tierId, 1);
    }

    public int computeScore(UUID uuid) {
        int score = 0;
        ConfigurationSection sec = data.getConfigurationSection(uuid + ".applied");
        if (sec == null) return 0;
        for (String tierId : sec.getKeys(false)) {
            score += sec.getInt(tierId, 0) * pointsOf(tierId);
        }
        return score;
    }

    public int score(UUID uuid) {
        return data.getInt(uuid + ".score", computeScore(uuid));
    }

    /** Una fila del ranking. */
    public record Entry(UUID uuid, String name, int score, int total) {
    }

    public List<Entry> top(int limit) {
        List<Entry> entries = new ArrayList<>();
        for (String key : data.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            int score = score(uuid);
            if (score <= 0) continue;
            entries.add(new Entry(uuid, name(uuid), score, totalApplied(uuid)));
        }
        entries.sort(Comparator.comparingInt(Entry::score).reversed());
        return entries.size() > limit ? entries.subList(0, limit) : entries;
    }

    /** Posicion en el ranking (1 = primero, 0 = sin puntuacion). */
    public int rankOf(UUID uuid) {
        int score = score(uuid);
        if (score <= 0) return 0;
        int rank = 1;
        for (String key : data.getKeys(false)) {
            try {
                UUID other = UUID.fromString(key);
                if (!other.equals(uuid) && score(other) > score) rank++;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return rank;
    }

    /** Desglose aplicado por tier, en el orden declarado en pools/tiers.yml. */
    public Map<String, Integer> breakdown(UUID uuid) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (TierRegistry.Tier tier : plugin.tiers().all()) {
            result.put(tier.id(), applied(uuid, tier.id()));
        }
        return result;
    }

    private String name(UUID uuid) {
        String stored = data.getString(uuid + ".name");
        if (stored != null && !stored.isBlank()) return stored;
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        return offline.getName() == null ? "???" : offline.getName();
    }

    private String path(Player player) {
        return player.getUniqueId().toString();
    }
}
