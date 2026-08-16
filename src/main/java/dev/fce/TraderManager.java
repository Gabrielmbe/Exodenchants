package dev.fce;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Encantador Errante — aldeano NPC que comercia libros del sistema.
 *
 * Cada aldeano nace con un stock aleatorio por tier; el éxito y la
 * ruptura de cada libro se tiran con las mismas tablas del tier que
 * usa la tienda, así el NPC nunca vende libros "mejores". Los trades
 * tienen usos limitados y no se reabastecen. Config: modules/trader.yml.
 */
public final class TraderManager implements Listener {

    private final FabledCustomEnchantsPlugin plugin;
    private final NamespacedKey traderKey;
    private final Random random = new Random();

    private String displayName = "<gradient:#7B2CBF:#C77DFF>✦ Encantador Errante ✦</gradient>";
    private final Map<String, StockRule> stock = new LinkedHashMap<>();
    private final List<DustRule> dustRules = new ArrayList<>();

    private record StockRule(int count, int levelMax, int uses, Map<Material, Integer> price) {}
    private record DustRule(String id, int amount, int uses, Map<Material, Integer> price) {}

    public TraderManager(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
        this.traderKey = new NamespacedKey(plugin, "fe_trader");
    }

    public void load() {
        stock.clear();
        dustRules.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "modules/trader.yml"));
        displayName = yml.getString("trader.name", displayName);

        ConfigurationSection stockSec = yml.getConfigurationSection("trader.stock");
        if (stockSec != null) {
            for (String tierId : stockSec.getKeys(false)) {
                ConfigurationSection sec = stockSec.getConfigurationSection(tierId);
                if (sec == null) continue;
                Map<Material, Integer> price = readPrice(sec.getConfigurationSection("price"));
                if (price.isEmpty()) continue;
                stock.put(tierId.toLowerCase(Locale.ROOT), new StockRule(
                        Math.max(0, sec.getInt("count", 0)),
                        Math.max(1, sec.getInt("level-max", 1)),
                        Math.max(1, sec.getInt("uses", 1)),
                        price));
            }
        }

        for (Map<?, ?> raw : yml.getMapList("trader.dusts")) {
            Object id = raw.get("id");
            if (id == null) continue;
            Map<Material, Integer> price = new LinkedHashMap<>();
            if (raw.get("price") instanceof Map<?, ?> priceMap) {
                for (Map.Entry<?, ?> entry : priceMap.entrySet()) {
                    Material mat = Material.matchMaterial(String.valueOf(entry.getKey()));
                    int amount = intOf(entry.getValue(), 0);
                    if (mat != null && amount > 0) price.put(mat, Math.min(64, amount));
                }
            }
            if (price.isEmpty()) continue;
            dustRules.add(new DustRule(
                    String.valueOf(id).toLowerCase(Locale.ROOT),
                    Math.max(1, intOf(raw.get("amount"), 1)),
                    Math.max(1, intOf(raw.get("uses"), 1)),
                    price));
        }
    }

    /** Invoca un Encantador Errante en la posición del jugador. */
    public void spawn(Player player) {
        List<MerchantRecipe> trades = buildTrades();
        player.getWorld().spawn(player.getLocation(), Villager.class, vil -> {
            vil.setProfession(Villager.Profession.LIBRARIAN);
            vil.setVillagerLevel(5);
            vil.setAI(false);
            vil.setInvulnerable(true);
            vil.setPersistent(true);
            vil.setRemoveWhenFarAway(false);
            vil.setCanPickupItems(false);
            vil.customName(MiniMessage.miniMessage().deserialize(displayName));
            vil.setCustomNameVisible(true);
            vil.getPersistentDataContainer().set(traderKey, PersistentDataType.BYTE, (byte) 1);
            vil.setRecipes(trades);
        });
    }

    /** Elimina los comerciantes marcados en un radio. Devuelve cuántos quitó. */
    public int removeNearby(Player player, double radius) {
        int removed = 0;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (isTrader(entity)) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Regenera el stock del comerciante más cercano. */
    public boolean refreshNearby(Player player, double radius) {
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (isTrader(entity) && entity instanceof Villager villager) {
                villager.setRecipes(buildTrades());
                return true;
            }
        }
        return false;
    }

    private List<MerchantRecipe> buildTrades() {
        List<MerchantRecipe> recipes = new ArrayList<>();

        for (Map.Entry<String, StockRule> entry : stock.entrySet()) {
            TierRegistry.Tier tier = plugin.tiers().get(entry.getKey());
            StockRule rule = entry.getValue();
            if (tier == null || rule.count() <= 0) continue;

            List<EnchantDefinition> pool = new ArrayList<>(plugin.enchants().byTier(entry.getKey()));
            Collections.shuffle(pool, random);

            int picked = 0;
            for (EnchantDefinition def : pool) {
                if (picked >= rule.count()) break;
                int level = 1 + random.nextInt(Math.max(1, Math.min(rule.levelMax(), def.maxLevel())));
                int success = plugin.tiers().rollSuccess(tier);
                int destroy = plugin.tiers().rollDestroy(tier, success);
                ItemStack book = plugin.books().create(def, tier, level, success, destroy);
                recipes.add(recipe(book, rule.uses(), rule.price(), level));
                picked++;
            }
        }

        for (DustRule rule : dustRules) {
            DustRegistry.Dust dust = plugin.dusts().get(rule.id());
            if (dust == null) continue;
            recipes.add(recipe(plugin.dusts().create(dust, rule.amount()), rule.uses(), rule.price(), 1));
        }

        return recipes;
    }

    /** El precio escala con el nivel del libro (tope: 64 por ingrediente, 2 ingredientes). */
    private MerchantRecipe recipe(ItemStack result, int uses, Map<Material, Integer> price, int level) {
        MerchantRecipe recipe = new MerchantRecipe(result, 0, uses, false);
        int added = 0;
        for (Map.Entry<Material, Integer> ing : price.entrySet()) {
            if (added >= 2) break;
            recipe.addIngredient(new ItemStack(ing.getKey(),
                    Math.min(64, ing.getValue() * Math.max(1, level))));
            added++;
        }
        return recipe;
    }

    private Map<Material, Integer> readPrice(ConfigurationSection sec) {
        Map<Material, Integer> price = new LinkedHashMap<>();
        if (sec == null) return price;
        for (String key : sec.getKeys(false)) {
            Material mat = Material.matchMaterial(key);
            int amount = sec.getInt(key, 0);
            if (mat != null && amount > 0) price.put(mat, Math.min(64, amount));
        }
        return price;
    }

    private static int intOf(Object value, int def) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return def;
        }
    }

    private boolean isTrader(Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(traderKey, PersistentDataType.BYTE);
    }

    // --- Protección del NPC ---

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (isTrader(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler
    public void onTransform(EntityTransformEvent event) {
        if (isTrader(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler
    public void onCareerChange(VillagerCareerChangeEvent event) {
        if (isTrader(event.getEntity())) event.setCancelled(true);
    }
}
