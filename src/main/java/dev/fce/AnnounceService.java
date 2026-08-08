package dev.fce;

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
}
