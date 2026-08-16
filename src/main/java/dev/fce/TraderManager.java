package dev.fce;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

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
 *
 * NOVEDADES v2 ("El Errante"):
 *  - HUEVO DE INVOCACIÓN: ítem con lore propio (fe_trader_egg). Al usarlo
 *    sobre un bloque consume el huevo e invoca al Encantador con una
 *    ceremonia de partículas y sonido. /fce trader egg [cantidad] lo
 *    entrega (fce.admin); los admins pueden repartirlo como recompensa
 *    de eventos, crates, votos, etc.
 *  - SUBTÍTULO HOLOGRÁFICO: un TextDisplay montado sobre el NPC muestra
 *    su título ("Mercader de Reliquias Arcanas"), configurable.
 *  - AURA ARCANA: tarea periódica que dibuja una doble hélice de polvo
 *    violeta, runas orbitando la cabeza y un halo de luz en el suelo,
 *    con campanadas de amatista ocasionales. trader.aura.enabled.
 *  - El NPC es silencioso (sin "hmm"): su presencia la marca el aura.
 *
 * SEGURIDAD: la GUI de merchant de este NPC está exenta del bloqueo
 * antidupe (ver AntiDupeListener.isOwnTrader); sus trades los construye
 * este plugin y la transacción la ejecuta el código vanilla.
 */
public final class TraderManager implements Listener {

    private final FabledCustomEnchantsPlugin plugin;
    private final NamespacedKey traderKey;
    private final NamespacedKey eggKey;
    private final Random random = new Random();

    private BukkitTask auraTask;
    private double auraAngle = 0.0;

    private String displayName = "<gradient:#7B2CBF:#C77DFF>✦ Encantador Errante ✦</gradient>";
    private String subtitle = "<gradient:#C77DFF:#E0AAFF><i>Mercader de Reliquias Arcanas</i></gradient>";
    private boolean auraEnabled = true;
    private String eggName = "<gradient:#7B2CBF:#C77DFF>✦ Huevo del Encantador Errante ✦</gradient>";
    private List<String> eggLore = List.of(
            "<dark_gray>│ <gray>Nadie sabe de qué mundo procede.",
            "<dark_gray>│ <gray>Camina entre dimensiones, coleccionando",
            "<dark_gray>│ <gray>saberes que los mortales olvidaron.",
            "<dark_gray>│",
            "<dark_gray>│ <#C77DFF>Úsalo sobre el suelo para invocar",
            "<dark_gray>│ <#C77DFF>al Encantador Errante.",
            "<dark_gray>│",
            "<dark_gray>│ <gray>Comercia <white>libros</white>, <white>polvos</white> y <white>esencias</white>",
            "<dark_gray>│ <gray>con stock <white>aleatorio</white> y <white>limitado</white>.",
            "<dark_gray>│",
            "<dark_gray>│ <#9D4EDD>✦ Reliquia de invocación · un solo uso ✦"
    );

    private final Map<String, StockRule> stock = new LinkedHashMap<>();
    private final List<DustRule> dustRules = new ArrayList<>();

    private record StockRule(int count, int levelMax, int uses, Map<Material, Integer> price) {}
    private record DustRule(String id, int amount, int uses, Map<Material, Integer> price) {}

    public TraderManager(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
        this.traderKey = new NamespacedKey(plugin, "fe_trader");
        this.eggKey = new NamespacedKey(plugin, "fe_trader_egg");
    }

    public void load() {
        stock.clear();
        dustRules.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "modules/trader.yml"));
        displayName = yml.getString("trader.name", displayName);
        subtitle = yml.getString("trader.subtitle", subtitle);
        auraEnabled = yml.getBoolean("trader.aura.enabled", true);
        eggName = yml.getString("trader.egg.name", eggName);
        List<String> confLore = yml.getStringList("trader.egg.lore");
        if (!confLore.isEmpty()) eggLore = confLore;

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

    /* ==================== Invocación ==================== */

    /** Invoca un Encantador Errante en la posición del jugador (comando admin). */
    public void spawn(Player player) {
        summon(player.getLocation(), player);
    }

    /**
     * Ceremonia de invocación: aldeano protegido + subtítulo holográfico +
     * estallido de partículas y campanadas. El NPC queda mirando al invocador.
     */
    private void summon(Location loc, Player summoner) {
        World world = loc.getWorld();
        if (world == null) return;

        Location spawnLoc = loc.clone();
        if (summoner != null) {
            Vector dir = summoner.getLocation().toVector().subtract(spawnLoc.toVector());
            dir.setY(0);
            if (dir.lengthSquared() > 0.01) spawnLoc.setDirection(dir);
        }

        List<MerchantRecipe> trades = buildTrades();
        Villager vil = world.spawn(spawnLoc, Villager.class, v -> {
            v.setProfession(Villager.Profession.LIBRARIAN);
            v.setVillagerLevel(5);
            v.setAI(false);
            v.setSilent(true); // sin "hmm": su presencia la marca el aura
            v.setInvulnerable(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setCanPickupItems(false);
            v.customName(mm(displayName));
            v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(traderKey, PersistentDataType.BYTE, (byte) 1);
            v.setRecipes(trades);
        });

        // Subtítulo holográfico sobre el nametag (TextDisplay pasajero).
        TextDisplay display = world.spawn(spawnLoc, TextDisplay.class, d -> {
            d.text(mm(subtitle));
            d.setBillboard(Display.Billboard.CENTER);
            d.setShadowed(true);
            d.setDefaultBackground(false);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setPersistent(true);
            Transformation t = d.getTransformation();
            d.setTransformation(new Transformation(
                    new Vector3f(0f, 0.95f, 0f),
                    t.getLeftRotation(), t.getScale(), t.getRightRotation()));
            d.getPersistentDataContainer().set(traderKey, PersistentDataType.BYTE, (byte) 1);
        });
        vil.addPassenger(display);

        summonFx(world, spawnLoc);
        if (summoner != null) {
            plugin.messages().sendRaw(summoner,
                    "<gradient:#7B2CBF:#C77DFF>✦ El Encantador Errante ha respondido a tu llamado ✦</gradient>");
        }
    }

    /** Estallido de invocación: columna de portal, runas, destello y campanadas. */
    private void summonFx(World world, Location loc) {
        Location center = loc.clone().add(0, 1.0, 0);
        world.spawnParticle(Particle.REVERSE_PORTAL, center, 140, 0.4, 1.0, 0.4, 0.08);
        world.spawnParticle(Particle.ENCHANT, center.clone().add(0, 0.6, 0), 180, 0.6, 0.8, 0.6, 1.2);
        world.spawnParticle(Particle.END_ROD, center, 40, 0.5, 0.9, 0.5, 0.05);
        world.spawnParticle(Particle.FLASH, center, 1);
        world.playSound(loc, "block.beacon.activate", 1.0f, 1.4f);
        world.playSound(loc, "entity.illusioner.cast_spell", 1.0f, 0.8f);
        world.playSound(loc, "block.amethyst_block.resonate", 1.0f, 0.7f);
    }

    /* ==================== Huevo de invocación ==================== */

    /** Crea el Huevo del Encantador Errante (lore narrativo + brillo). */
    public ItemStack createEgg(int amount) {
        ItemStack egg = new ItemStack(Material.VILLAGER_SPAWN_EGG,
                Math.max(1, Math.min(16, amount)));
        ItemMeta meta = egg.getItemMeta();
        meta.displayName(mm(eggName));
        List<Component> lore = new ArrayList<>();
        for (String line : eggLore) lore.add(mm(line));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(eggKey, PersistentDataType.BYTE, (byte) 1);
        egg.setItemMeta(meta);
        return egg;
    }

    /** Entrega huevos de invocación al jugador. */
    public void giveEgg(Player player, int amount) {
        player.getInventory().addItem(createEgg(amount));
        player.playSound(player.getLocation(), "block.amethyst_block.chime", 1.0f, 1.2f);
    }

    private boolean isEgg(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                        .has(eggKey, PersistentDataType.BYTE);
    }

    /** Uso del huevo sobre un bloque: consume e invoca con ceremonia. */
    @EventHandler
    public void onEggUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (!isEgg(item)) return;

        // Nunca dejar que el huevo actúe como spawn egg vanilla.
        event.setCancelled(true);
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;

        Player player = event.getPlayer();
        Location loc = event.getClickedBlock()
                .getRelative(event.getBlockFace())
                .getLocation().add(0.5, 0, 0.5);

        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
        summon(loc, player);
    }

    /** Bloquea usar el huevo sobre entidades (evitaría la ceremonia). */
    @EventHandler
    public void onEggEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (isEgg(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    /* ==================== Aura arcana ==================== */

    /**
     * Arranca la tarea del aura (idempotente; llamar desde onEnable).
     * Doble hélice violeta + runas en la cabeza + halo en el suelo,
     * con campanadas de amatista ocasionales.
     */
    public void startAura() {
        if (auraTask != null) return;
        auraTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAura, 40L, 6L);
    }

    private void tickAura() {
        if (!auraEnabled) return;
        auraAngle += 0.38;
        for (World world : Bukkit.getWorlds()) {
            for (Villager vil : world.getEntitiesByClass(Villager.class)) {
                if (!isTrader(vil)) continue;
                Location base = vil.getLocation();

                // Doble hélice de polvo violeta ascendente
                for (int i = 0; i < 2; i++) {
                    double angle = auraAngle + i * Math.PI;
                    double y = 0.15 + ((auraAngle * 0.45 + i * 1.1) % 2.2);
                    world.spawnParticle(Particle.DUST,
                            base.clone().add(Math.cos(angle) * 0.85, y, Math.sin(angle) * 0.85),
                            1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.fromRGB(0x9D4EDD), 1.1f));
                }

                // Runas de encantamiento orbitando la cabeza
                world.spawnParticle(Particle.ENCHANT,
                        base.clone().add(0, 2.1, 0), 4, 0.35, 0.25, 0.35, 0.6);

                // Halo de luz girando en el suelo
                double halo = auraAngle * 1.6;
                world.spawnParticle(Particle.END_ROD,
                        base.clone().add(Math.cos(halo) * 1.15, 0.08, Math.sin(halo) * 1.15),
                        1, 0, 0, 0, 0);

                // Campanada ocasional (~cada 12 s de media)
                if (random.nextInt(40) == 0) {
                    world.playSound(base, "block.amethyst_block.chime", 0.5f, 0.8f);
                }
            }
        }
    }

    /* ==================== Gestión ==================== */

    /** Elimina los comerciantes marcados en un radio (incluye su holograma). */
    public int removeNearby(Player player, double radius) {
        int removed = 0;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (isTrader(entity)) {
                for (Entity passenger : entity.getPassengers()) {
                    if (isTrader(passenger)) passenger.remove();
                }
                if (entity instanceof Villager) removed++;
                entity.remove();
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

    /* ==================== Trades ==================== */

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

    private static Component mm(String miniMessage) {
        return MiniMessage.miniMessage().deserialize(miniMessage)
                .decoration(TextDecoration.ITALIC, false);
    }

    /* ==================== Protección del NPC ==================== */

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
