package dev.fce;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * MERCADO NEGRO.
 *
 * Ofertas que rotan cada N horas: libros con el % de exito ya mejorado y
 * consumibles con descuento. La rotacion es DETERMINISTA — se calcula a partir
 * de la marca de tiempo dividida por el periodo, asi que todos los jugadores
 * ven exactamente lo mismo sin necesidad de persistir nada, y el catalogo
 * cambia solo al cumplirse el plazo.
 */
public class BlackMarketManager {

    /** Una oferta del catalogo actual. */
    public record Offer(String kind, String id, int level, int success, int destroy,
                        double price, int discount) {

        public boolean isBook() {
            return "book".equals(kind);
        }
    }

    private final FabledCustomEnchantsPlugin plugin;

    private boolean enabled = true;
    private int periodHours = 6;
    private int offerCount = 4;
    private List<Integer> slots = List.of(11, 12, 13, 14, 15);
    private int discountMin = 15;
    private int discountMax = 45;
    private int successBonusMin = 10;
    private int successBonusMax = 30;
    private double dustChance = 35;

    private long cachedRotation = Long.MIN_VALUE;
    private List<Offer> cachedOffers = List.of();

    public BlackMarketManager(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "modules/black_market.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        enabled = yml.getBoolean("enabled", true);
        periodHours = Math.max(1, yml.getInt("rotation-hours", 6));
        offerCount = Math.max(1, yml.getInt("offer-count", 4));
        List<Integer> configured = yml.getIntegerList("slots");
        if (!configured.isEmpty()) slots = configured;
        discountMin = yml.getInt("discount.min", 15);
        discountMax = Math.max(discountMin, yml.getInt("discount.max", 45));
        successBonusMin = yml.getInt("success-bonus.min", 10);
        successBonusMax = Math.max(successBonusMin, yml.getInt("success-bonus.max", 30));
        dustChance = yml.getDouble("dust-chance", 35);
        cachedRotation = Long.MIN_VALUE; // fuerza regenerar con la config nueva
        plugin.getLogger().info("Mercado negro: " + (enabled ? "activo" : "desactivado")
                + " · rotacion cada " + periodHours + "h");
    }

    public boolean enabled() {
        return enabled;
    }

    public List<Integer> slots() {
        return slots;
    }

    private long periodMillis() {
        return periodHours * 3600_000L;
    }

    /** Indice de rotacion: cambia al cumplirse el periodo. */
    public long rotationIndex() {
        return System.currentTimeMillis() / periodMillis();
    }

    public long millisUntilNextRotation() {
        long period = periodMillis();
        return period - (System.currentTimeMillis() % period);
    }

    /** "5h 42m" — cuenta atras legible para la GUI y los placeholders. */
    public String timeLeft() {
        long millis = millisUntilNextRotation();
        long hours = millis / 3600_000L;
        long minutes = (millis % 3600_000L) / 60_000L;
        if (hours > 0) return hours + "h " + minutes + "m";
        long seconds = (millis % 60_000L) / 1000L;
        return minutes + "m " + seconds + "s";
    }

    /** Catalogo actual, cacheado por rotacion. */
    public List<Offer> offers() {
        long rotation = rotationIndex();
        if (rotation == cachedRotation) return cachedOffers;
        cachedOffers = generate(rotation);
        cachedRotation = rotation;
        return cachedOffers;
    }

    /**
     * Genera las ofertas con una semilla derivada de la rotacion: mismo
     * catalogo para todo el servidor, distinto en cada ciclo.
     */
    private List<Offer> generate(long rotation) {
        List<Offer> result = new ArrayList<>();
        Random random = new Random(rotation * 31L + 7L);

        List<EnchantDefinition> enchants = new ArrayList<>(plugin.enchants().all());
        List<DustRegistry.Dust> dusts = new ArrayList<>(plugin.dusts().all());
        if (enchants.isEmpty()) return result;

        int target = Math.min(offerCount, slots.size());
        int guard = 0;
        while (result.size() < target && guard++ < 200) {
            boolean wantDust = !dusts.isEmpty() && random.nextDouble() * 100 < dustChance;
            int discount = discountMin + random.nextInt(discountMax - discountMin + 1);

            if (wantDust) {
                DustRegistry.Dust dust = dusts.get(random.nextInt(dusts.size()));
                if (containsId(result, dust.id())) continue;
                double price = Math.max(1, Math.round(dust.price() * (100 - discount) / 100.0));
                result.add(new Offer("dust", dust.id(), 1, 0, 0, price, discount));
                continue;
            }

            EnchantDefinition def = pickWeighted(enchants, random);
            TierRegistry.Tier tier = plugin.tiers().get(def.tierId());
            if (tier == null || containsId(result, def.id())) continue;

            int maxLevel = Math.max(1, Math.min(tier.maxBookLevel(), def.maxLevel()));
            int level = 1 + random.nextInt(maxLevel);
            int bonus = successBonusMin + random.nextInt(successBonusMax - successBonusMin + 1);
            int success = Math.min(100, tier.successMax() + bonus);
            int destroy = Math.max(0, tier.destroyMin());
            double price = Math.max(1, Math.round(tier.price() * (100 - discount) / 100.0));
            result.add(new Offer("book", def.id(), level, success, destroy, price, discount));
        }
        return result;
    }

    /**
     * Eleccion ponderada por el peso del tier (pools/tiers.yml): un libro
     * Divino (peso 1) aparece en el mercado tan raramente como en el loot
     * normal, en lugar de salir tan a menudo como un Comun (peso 60).
     */
    private EnchantDefinition pickWeighted(List<EnchantDefinition> enchants, Random random) {
        int total = 0;
        for (EnchantDefinition def : enchants) {
            TierRegistry.Tier tier = plugin.tiers().get(def.tierId());
            total += tier == null ? 1 : Math.max(1, tier.weight());
        }
        int roll = random.nextInt(Math.max(1, total));
        for (EnchantDefinition def : enchants) {
            TierRegistry.Tier tier = plugin.tiers().get(def.tierId());
            roll -= tier == null ? 1 : Math.max(1, tier.weight());
            if (roll < 0) return def;
        }
        return enchants.get(enchants.size() - 1);
    }

    private boolean containsId(List<Offer> offers, String id) {
        for (Offer offer : offers) {
            if (offer.id().equalsIgnoreCase(id)) return true;
        }
        return false;
    }

    /** Icono de la oferta para la GUI. */
    public ItemStack icon(Offer offer) {
        if (offer.isBook()) {
            EnchantDefinition def = plugin.enchants().get(offer.id());
            TierRegistry.Tier tier = def == null ? null : plugin.tiers().get(def.tierId());
            if (def == null || tier == null) return new ItemStack(Material.ENCHANTED_BOOK);
            return plugin.books().create(def, tier, offer.level(), offer.success(), offer.destroy());
        }
        DustRegistry.Dust dust = plugin.dusts().get(offer.id());
        return dust == null ? new ItemStack(Material.SUGAR) : plugin.dusts().create(dust, 1);
    }

    /** Nombre legible de la oferta (para el Lore y los mensajes). */
    public String displayName(Offer offer) {
        if (offer.isBook()) {
            EnchantDefinition def = plugin.enchants().get(offer.id());
            return def == null ? offer.id() : def.displayName();
        }
        DustRegistry.Dust dust = plugin.dusts().get(offer.id());
        return dust == null ? offer.id() : dust.displayName();
    }

    public String tierDisplay(Offer offer) {
        if (!offer.isBook()) return "<gray>Consumible";
        EnchantDefinition def = plugin.enchants().get(offer.id());
        TierRegistry.Tier tier = def == null ? null : plugin.tiers().get(def.tierId());
        return tier == null ? "" : tier.display();
    }

    /**
     * Compra por indice del catalogo actual. Devuelve true si se entrego.
     *
     * TRANSACCION ATOMICA: el articulo se resuelve y se construye ANTES de
     * cobrar (si la oferta apunta a un encantamiento o polvo que ya no existe
     * tras un reload, no se toca el dinero). Si la entrega fallara despues
     * del cobro, se reembolsa el importe completo y se registra en consola.
     */
    public boolean buy(Player player, int index) {
        List<Offer> current = offers();
        if (index < 0 || index >= current.size()) return false;
        Offer offer = current.get(index);

        // 1) Resolver y construir el articulo ANTES de cobrar
        ItemStack item;
        EnchantDefinition def = null;
        TierRegistry.Tier tier = null;
        if (offer.isBook()) {
            def = plugin.enchants().get(offer.id());
            tier = def == null ? null : plugin.tiers().get(def.tierId());
            if (def == null || tier == null) return false;
            item = plugin.books().create(def, tier, offer.level(), offer.success(), offer.destroy());
        } else {
            DustRegistry.Dust dust = plugin.dusts().get(offer.id());
            if (dust == null) return false;
            item = plugin.dusts().create(dust, 1);
        }

        // 2) Cobrar
        if (!plugin.vault().withdraw(player, offer.price())) {
            plugin.messages().playSound(player, "purchase-denied");
            plugin.messages().send(player, "no-money",
                    "precio", String.format(Locale.US, "%,.0f", offer.price()));
            return false;
        }

        // 3) Entregar; si algo falla, reembolso integro
        try {
            give(player, item);
        } catch (Exception ex) {
            plugin.vault().deposit(player, offer.price());
            plugin.getLogger().severe("[Mercado] Entrega fallida tras el cobro ("
                    + offer.id() + ", " + player.getName() + "): reembolsados "
                    + String.format(Locale.US, "%,.0f", offer.price()) + ". Causa: " + ex);
            plugin.messages().playSound(player, "purchase-denied");
            return false;
        }

        if (def != null) {
            plugin.announcer().purchased(player, def, tier, offer.price());
        }
        plugin.messages().playSound(player, "purchase-success");
        plugin.messages().send(player, "market-bought",
                "articulo", displayName(offer),
                "descuento", String.valueOf(offer.discount()));
        return true;
    }

    private void give(Player player, ItemStack stack) {
        player.getInventory().addItem(stack).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
    }
}
