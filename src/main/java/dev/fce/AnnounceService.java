package dev.fce;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * ANUNCIOS GLOBALES.
 *
 * Solo se anuncia lo que de verdad merece atencion, segun los umbrales de
 * config.yml -> announce:
 *
 *   · min-tier-points : el tier debe valer al menos N puntos de ranking
 *                       (asi Comun y Raro nunca hacen ruido).
 *   · min-price       : o bien el libro debe costar al menos N.
 *   · max-success     : o bien haberse aplicado con un % de exito bajisimo
 *                       (la gesta improbable).
 *
 * Cualquiera de las tres condiciones basta: un Divino siempre suena, y un
 * Legendario clavado al 15% tambien.
 *
 * REGLA DE ORO de los servidores: lo que se anuncia en el chat, la gente lo
 * persigue. Por eso los momentos raros son ruidosos: aplicaciones dificiles,
 * rupturas de items valiosos, supervivencias por un pelo y golpes de suerte
 * en la trituradora. Los mensajes admiten override en config.yml ->
 * messages.announce-survived / messages.announce-jackpot (con defaults
 * integrados: funcionan aunque el config sea de una version vieja).
 */
public class AnnounceService {

    private final FabledCustomEnchantsPlugin plugin;

    public AnnounceService(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("announce.enabled", true);
    }

    /** ¿Merece anuncio este libro? */
    public boolean worthy(TierRegistry.Tier tier, int success) {
        if (tier == null) return false;
        int minPoints = plugin.getConfig().getInt("announce.min-tier-points", 5);
        double minPrice = plugin.getConfig().getDouble("announce.min-price", 90000);
        int maxSuccess = plugin.getConfig().getInt("announce.max-success", 20);
        return plugin.stats().pointsOf(tier.id()) >= minPoints
                || tier.price() >= minPrice
                || success <= maxSuccess;
    }

    /** Aplicacion exitosa de un libro difícil: anuncio + sonido global. */
    public void applied(Player player, EnchantDefinition def, TierRegistry.Tier tier,
                        int level, int success) {
        if (!enabled() || !worthy(tier, success)) return;
        plugin.messages().broadcast("announce-applied",
                "jugador", player.getName(),
                "enchant", def.displayName(),
                "nivel_romano", BookFactory.roman(level),
                "tier", tier.display(),
                "exito", String.valueOf(success));
        plugin.messages().broadcastSound("announce");
    }

    /** Compra de un libro caro (tienda o mercado negro). */
    public void purchased(Player player, EnchantDefinition def, TierRegistry.Tier tier, double price) {
        if (!enabled()) return;
        if (!plugin.getConfig().getBoolean("announce.on-purchase", true)) return;
        double minPrice = plugin.getConfig().getDouble("announce.min-price", 90000);
        if (price < minPrice && plugin.stats().pointsOf(tier.id())
                < plugin.getConfig().getInt("announce.min-tier-points", 5)) return;
        plugin.messages().broadcast("announce-purchased",
                "jugador", player.getName(),
                "enchant", def.displayName(),
                "tier", tier.display());
    }

    /** Destruccion de un item con un libro de alto tier. */
    public void destroyed(Player player, EnchantDefinition def, TierRegistry.Tier tier) {
        if (!enabled()) return;
        if (!plugin.getConfig().getBoolean("announce.on-destroy", true)) return;
        if (!worthy(tier, 0)) return;
        plugin.messages().broadcast("announce-destroyed",
                "jugador", player.getName(),
                "enchant", def.displayName(),
                "tier", tier.display());
    }

    /** Item de alto tier que sobrevive a la ruptura por 1-3 puntos. */
    public void survived(Player player, EnchantDefinition def, TierRegistry.Tier tier) {
        if (!enabled()) return;
        if (!plugin.getConfig().getBoolean("announce.on-survived", true)) return;
        if (!worthy(tier, 0)) return;
        broadcastRaw("announce-survived",
                "<gradient:#FFD60A:#FFC300>⚡ ¡{jugador} salvó su objeto por un pelo!</gradient> "
                        + "<gray>{enchant} estuvo a punto de destruirlo...</gray>",
                "jugador", player.getName(),
                "enchant", def.displayName(),
                "tier", tier.display());
        plugin.messages().broadcastSound("announce");
    }

    /** Golpe de suerte en la trituradora: esencia extra entre los restos. */
    public void jackpot(Player player, String dustName) {
        if (!enabled()) return;
        if (!plugin.getConfig().getBoolean("announce.on-jackpot", true)) return;
        broadcastRaw("announce-jackpot",
                "<gradient:#8ECAE6:#219EBC>✨ ¡{jugador} tuvo un golpe de suerte en la trituradora!</gradient> "
                        + "<gray>Encontró <white>{polvo}</white> entre los restos.</gray>",
                "jugador", player.getName(),
                "polvo", dustName);
    }

    /**
     * Broadcast con default integrado: usa messages.<key> del config si existe
     * y, si no, el mensaje por defecto — asi los servidores con config.yml
     * antiguo tambien ven los anuncios nuevos sin tocar nada.
     */
    private void broadcastRaw(String key, String fallback, String... kv) {
        String raw = plugin.getConfig().getString("messages." + key, fallback);
        if (raw == null || raw.isBlank()) return;
        Bukkit.getServer().sendMessage(plugin.messages().render(raw, kv));
    }
}
