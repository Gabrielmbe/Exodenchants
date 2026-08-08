package dev.fce;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Expansion de PlaceholderAPI. Identificador: fce
 *
 *   %fce_score%              puntuacion de ranking
 *   %fce_rank%               posicion en el ranking
 *   %fce_applied%            encantamientos aplicados en total
 *   %fce_applied_<tier>%     aplicados de ese tier
 *   %fce_streak_<tier>%      fallos consecutivos de ese tier
 *   %fce_luck_<tier>%        bono de suerte acumulado (%)
 *   %fce_combo%              combo de set activo
 *   %fce_combo_count_<tier>% encantos equipados de ese tier
 *   %fce_market_time%        tiempo hasta la siguiente rotacion
 *   %fce_top_name_N%         nombre del puesto N
 *   %fce_top_score_N%        puntuacion del puesto N
 *   %fce_total_enchants%     encantamientos disponibles en el sistema
 */
public class PlaceholderHook extends PlaceholderExpansion {

    private final FabledCustomEnchantsPlugin plugin;

    public PlaceholderHook(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "fce";
    }

    @Override
    public String getAuthor() {
        return "FabledCustomEnchants";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offline, String params) {
        String key = params.toLowerCase(Locale.ROOT);

        if (key.equals("total_enchants")) return String.valueOf(plugin.enchants().all().size());
        if (key.equals("market_time")) return plugin.market().timeLeft();

        if (key.startsWith("top_name_") || key.startsWith("top_score_")) {
            boolean wantName = key.startsWith("top_name_");
            int position = parse(key.substring(key.lastIndexOf('_') + 1));
            if (position < 1) return "";
            List<PlayerStats.Entry> top = plugin.stats().top(Math.max(position, 10));
            if (position > top.size()) return wantName ? "—" : "0";
            PlayerStats.Entry entry = top.get(position - 1);
            return wantName ? entry.name() : String.valueOf(entry.score());
        }

        if (offline == null) return "";

        switch (key) {
            case "score" -> {
                return String.valueOf(plugin.stats().score(offline.getUniqueId()));
            }
            case "rank" -> {
                int rank = plugin.stats().rankOf(offline.getUniqueId());
                return rank == 0 ? "—" : String.valueOf(rank);
            }
            case "applied" -> {
                return String.valueOf(plugin.stats().totalApplied(offline.getUniqueId()));
            }
            default -> {
            }
        }

        if (key.startsWith("applied_")) {
            return String.valueOf(plugin.stats().applied(offline.getUniqueId(), key.substring(8)));
        }
        if (key.startsWith("streak_")) {
            return String.valueOf(plugin.stats().streak(offline.getUniqueId(), key.substring(7)));
        }

        Player online = offline.getPlayer();
        if (key.startsWith("luck_")) {
            if (online == null) return "0";
            return String.valueOf(plugin.stats().luckBonus(online, key.substring(5)));
        }
        if (key.equals("combo")) {
            if (online == null) return "—";
            SetComboManager.Combo combo = plugin.combos().activeCombo(online);
            return combo == null ? "—" : combo.display();
        }
        if (key.startsWith("combo_count_")) {
            if (online == null) return "0";
            return String.valueOf(plugin.combos().countByTier(online)
                    .getOrDefault(key.substring(12), 0));
        }
        return null;
    }

    private int parse(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
