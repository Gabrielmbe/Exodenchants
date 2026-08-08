package dev.fce;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MOTOR DE MECANICAS DE HERRAMIENTA.
 *
 * Implementa comportamientos que ningun sistema de componentes de skills puede
 * expresar, porque manipulan bloques, drops, experiencia y durabilidad:
 *
 *  PICOS
 *   vein_miner      · extrae la veta completa del mismo mineral
 *   area_break      · rompe un area 3x3 en el plano de la cara golpeada
 *   auto_smelt      · funde los minerales al instante (+XP)
 *   telekinesis     · drops y XP directos al inventario
 *   drop_multiplier · probabilidad de multiplicar los drops
 *   xp_boost        · experiencia adicional al excavar
 *   ore_reveal      · revela minerales cercanos con destellos
 *   self_repair     · probabilidad de reparar la propia herramienta
 *   charge_blast    · acumula cargas y libera una detonacion 5x5
 *
 *  HACHAS
 *   tree_feller     · tumba el arbol completo de un golpe
 *   auto_replant    · replanta un brote en el tocon
 *   lumber_haul     · tablones extra por cada tronco
 *   sap_harvest     · probabilidad de resina, panal o palos
 *   bark_strip      · probabilidad de tronco extra (descortezado)
 *   leaf_clear      · despeja el follaje alrededor
 *
 * Todas las roturas adicionales lanzan su propio BlockBreakEvent, de modo que
 * los plugins de protección (regiones, claims, anti-grief) puedan vetarlas.
 */
public class ToolMechanicsListener implements Listener {

    /** Techos de seguridad: evitan que una veta o un arbol congelen el servidor. */
    private static final int HARD_LIMIT = 220;

    private static final Map<Material, Material> SMELT = new HashMap<>();
    private static final BlockFace[] NEIGHBOURS = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    static {
        SMELT.put(Material.RAW_IRON, Material.IRON_INGOT);
        SMELT.put(Material.RAW_GOLD, Material.GOLD_INGOT);
        SMELT.put(Material.RAW_COPPER, Material.COPPER_INGOT);
        SMELT.put(Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP);
        SMELT.put(Material.COBBLESTONE, Material.STONE);
        SMELT.put(Material.COBBLED_DEEPSLATE, Material.DEEPSLATE);
        SMELT.put(Material.SAND, Material.GLASS);
        SMELT.put(Material.CLAY_BALL, Material.BRICK);
    }

    private final FabledCustomEnchantsPlugin plugin;
    /** Reentrada: mientras rompemos bloques en cadena, ignoramos nuestros eventos. */
    private final Set<UUID> busy = new HashSet<>();
    /** Cargas acumuladas de charge_blast, por jugador. */
    private final Map<UUID, Integer> charges = new HashMap<>();

    public ToolMechanicsListener(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (busy.contains(player.getUniqueId())) return;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null || tool.getType().isAir()) return;

        Map<String, Integer> mechanics = mechanicsOf(tool);
        if (mechanics.isEmpty()) return;

        Block origin = event.getBlock();

        // --- Modificadores de botin ---
        boolean smelt = roll(tool, "auto_smelt", mechanics);
        boolean telekinesis = mechanics.containsKey("telekinesis");
        int multiplier = dropMultiplier(tool, mechanics);
        double xpMultiplier = xpMultiplier(tool, mechanics);

        // --- Bloques adicionales ---
        List<Block> extra = new ArrayList<>();
        Integer vein = mechanics.get("vein_miner");
        if (vein != null && isOre(origin.getType())) {
            extra.addAll(connected(origin, limit(tool, "vein_miner", vein), true));
        }
        Integer feller = mechanics.get("tree_feller");
        if (feller != null && Tag.LOGS.isTagged(origin.getType())) {
            extra.addAll(connected(origin, limit(tool, "tree_feller", feller), false));
        }
        Integer area = mechanics.get("area_break");
        if (area != null) {
            extra.addAll(plane(origin, player, 1));
        }
        Integer leaves = mechanics.get("leaf_clear");
        if (leaves != null && (Tag.LOGS.isTagged(origin.getType()) || Tag.LEAVES.isTagged(origin.getType()))) {
            extra.addAll(foliage(origin, (int) Math.round(value(tool, "leaf_clear", leaves))));
        }
        Integer blast = mechanics.get("charge_blast");
        if (blast != null && addCharge(player, tool, blast)) {
            extra.addAll(plane(origin, player, 2));
            player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.4f);
            player.getWorld().spawnParticle(Particle.EXPLOSION, origin.getLocation().add(0.5, 0.5, 0.5), 3);
            plugin.messages().send(player, "charge-released");
        }

        // --- Botin del bloque original ---
        List<ItemStack> loot = new ArrayList<>();
        boolean customLoot = smelt || telekinesis || multiplier > 1
                || mechanics.containsKey("lumber_haul") || mechanics.containsKey("sap_harvest")
                || mechanics.containsKey("bark_strip");
        if (customLoot) {
            loot.addAll(origin.getDrops(tool, player));
            event.setDropItems(false);
        }
        int xp = event.getExpToDrop();
        if (xpMultiplier > 1) {
            xp = (int) Math.round(xp * xpMultiplier);
            event.setExpToDrop(0);
        }

        // --- Bonus de hacha sobre el bloque original ---
        addWoodBonus(loot, origin, tool, mechanics);

        // --- Roturas en cadena ---
        if (!extra.isEmpty()) {
            busy.add(player.getUniqueId());
            try {
                for (Block block : extra) {
                    if (block.getType().isAir()) continue;
                    BlockBreakEvent chained = new BlockBreakEvent(block, player);
                    Bukkit.getPluginManager().callEvent(chained);
                    if (chained.isCancelled()) continue;

                    List<ItemStack> drops = new ArrayList<>(block.getDrops(tool, player));
                    addWoodBonus(drops, block, tool, mechanics);
                    Material broken = block.getType();
                    block.setType(Material.AIR, false);
                    loot.addAll(drops);
                    xp += chained.getExpToDrop();
                    if (mechanics.containsKey("auto_replant") && Tag.LOGS.isTagged(broken)) {
                        replant(block, broken);
                    }
                }
            } finally {
                busy.remove(player.getUniqueId());
            }
            if (!customLoot) {
                // No habia modificadores de botin, pero hay que entregar el de los extras
                customLoot = true;
            }
        }

        if (mechanics.containsKey("auto_replant") && Tag.LOGS.isTagged(origin.getType())) {
            replant(origin, origin.getType());
        }

        // --- Entrega del botin ---
        if (smelt) loot = smelt(loot);
        if (multiplier > 1) loot = multiply(loot, multiplier);

        if (customLoot && !loot.isEmpty()) {
            deliver(player, origin.getLocation(), loot, telekinesis);
        }
        if (xp > 0 && (telekinesis || xpMultiplier > 1)) {
            player.giveExp(xp);
            if (xpMultiplier > 1) event.setExpToDrop(0);
        }

        // --- Utilidades pasivas ---
        Integer repair = mechanics.get("self_repair");
        if (repair != null && roll(tool, "self_repair", mechanics)) repair(tool, repair);
        Integer reveal = mechanics.get("ore_reveal");
        if (reveal != null && roll(tool, "ore_reveal", mechanics)) reveal(player, origin, reveal);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        charges.remove(event.getPlayer().getUniqueId());
        busy.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------
    // RESOLUCION DE MECANICAS DEL ITEM
    // ------------------------------------------------------------
    /** type de mecanica -> nivel, para la herramienta en mano. */
    private Map<String, Integer> mechanicsOf(ItemStack tool) {
        Map<String, Integer> found = new LinkedHashMap<>();
        for (Map.Entry<EnchantDefinition, Integer> entry : plugin.enchants().onItem(tool).entrySet()) {
            EnchantDefinition def = entry.getKey();
            if (!def.hasMechanic()) continue;
            found.merge(def.mechanic().type(), entry.getValue(), Math::max);
        }
        return found;
    }

    private EnchantDefinition definitionFor(ItemStack tool, String mechanicType) {
        for (EnchantDefinition def : plugin.enchants().onItem(tool).keySet()) {
            if (def.hasMechanic() && def.mechanic().type().equals(mechanicType)) return def;
        }
        return null;
    }

    private double value(ItemStack tool, String mechanicType, int level) {
        EnchantDefinition def = definitionFor(tool, mechanicType);
        return def == null ? level : def.mechanic().valueAt(level);
    }

    private int limit(ItemStack tool, String mechanicType, int level) {
        return Math.max(1, Math.min(HARD_LIMIT, (int) Math.round(value(tool, mechanicType, level))));
    }

    private boolean roll(ItemStack tool, String mechanicType, Map<String, Integer> mechanics) {
        Integer level = mechanics.get(mechanicType);
        if (level == null) return false;
        EnchantDefinition def = definitionFor(tool, mechanicType);
        double chance = def == null ? 100 : def.mechanic().chanceAt(level);
        return chance >= 100 || ThreadLocalRandom.current().nextDouble(100) < chance;
    }

    private int dropMultiplier(ItemStack tool, Map<String, Integer> mechanics) {
        Integer level = mechanics.get("drop_multiplier");
        if (level == null) return 1;
        if (!roll(tool, "drop_multiplier", mechanics)) return 1;
        return Math.max(2, (int) Math.round(value(tool, "drop_multiplier", level)));
    }

    private double xpMultiplier(ItemStack tool, Map<String, Integer> mechanics) {
        Integer level = mechanics.get("xp_boost");
        if (level == null) return 1;
        return Math.max(1, value(tool, "xp_boost", level));
    }

    // ------------------------------------------------------------
    // SELECCION DE BLOQUES
    // ------------------------------------------------------------
    /** Busqueda en anchura de bloques conectados del mismo tipo (veta o arbol). */
    private List<Block> connected(Block origin, int max, boolean sameOreOnly) {
        List<Block> result = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        seen.add(origin);
        queue.add(origin);

        while (!queue.isEmpty() && result.size() < max) {
            Block current = queue.poll();
            for (BlockFace face : NEIGHBOURS) {
                Block next = current.getRelative(face);
                if (!seen.add(next)) continue;
                boolean match = sameOreOnly
                        ? matchesOre(origin.getType(), next.getType())
                        : Tag.LOGS.isTagged(next.getType());
                if (!match) continue;
                result.add(next);
                queue.add(next);
                if (result.size() >= max) break;
            }
            // Los arboles crecen tambien en diagonal (ramas)
            if (!sameOreOnly) {
                for (int dx = -1; dx <= 1 && result.size() < max; dx++) {
                    for (int dz = -1; dz <= 1 && result.size() < max; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        Block next = current.getRelative(dx, 1, dz);
                        if (!seen.add(next)) continue;
                        if (!Tag.LOGS.isTagged(next.getType())) continue;
                        result.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        return result;
    }

    /** Area cuadrada en el plano perpendicular a la cara mirada. */
    private List<Block> plane(Block origin, Player player, int radius) {
        List<Block> result = new ArrayList<>();
        BlockFace face = facing(player);
        for (int a = -radius; a <= radius; a++) {
            for (int b = -radius; b <= radius; b++) {
                if (a == 0 && b == 0) continue;
                Block block = switch (face) {
                    case UP, DOWN -> origin.getRelative(a, 0, b);
                    case NORTH, SOUTH -> origin.getRelative(a, b, 0);
                    default -> origin.getRelative(0, b, a);
                };
                if (block.getType().isAir() || !block.getType().isSolid()) continue;
                result.add(block);
            }
        }
        return result;
    }

    /** Follaje en un radio alrededor del bloque. */
    private List<Block> foliage(Block origin, int radius) {
        List<Block> result = new ArrayList<>();
        int r = Math.max(1, Math.min(4, radius));
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    Block block = origin.getRelative(x, y, z);
                    if (Tag.LEAVES.isTagged(block.getType())) result.add(block);
                }
            }
        }
        return result;
    }

    private BlockFace facing(Player player) {
        float pitch = player.getLocation().getPitch();
        if (pitch < -45) return BlockFace.UP;
        if (pitch > 45) return BlockFace.DOWN;
        return player.getFacing();
    }

    private boolean matchesOre(Material origin, Material candidate) {
        if (origin == candidate) return true;
        // Variantes de piedra base y profunda de un mismo mineral
        String a = origin.name().replace("DEEPSLATE_", "").replace("NETHER_", "");
        String b = candidate.name().replace("DEEPSLATE_", "").replace("NETHER_", "");
        return a.equals(b) && isOre(candidate);
    }

    private boolean isOre(Material material) {
        String name = material.name();
        return name.endsWith("_ORE") || material == Material.ANCIENT_DEBRIS
                || material == Material.GILDED_BLACKSTONE;
    }

    // ------------------------------------------------------------
    // BOTIN
    // ------------------------------------------------------------
    private void addWoodBonus(List<ItemStack> loot, Block block, ItemStack tool,
                              Map<String, Integer> mechanics) {
        if (!Tag.LOGS.isTagged(block.getType())) return;

        Integer haul = mechanics.get("lumber_haul");
        if (haul != null) {
            Material planks = derive(block.getType(), "_LOG", "_PLANKS");
            if (planks == null) planks = derive(block.getType(), "_WOOD", "_PLANKS");
            if (planks != null) {
                int amount = Math.max(1, (int) Math.round(value(tool, "lumber_haul", haul)));
                loot.add(new ItemStack(planks, amount));
            }
        }
        if (mechanics.containsKey("sap_harvest") && roll(tool, "sap_harvest", mechanics)) {
            loot.add(new ItemStack(ThreadLocalRandom.current().nextBoolean()
                    ? Material.HONEYCOMB : Material.STICK,
                    1 + ThreadLocalRandom.current().nextInt(2)));
        }
        if (mechanics.containsKey("bark_strip") && roll(tool, "bark_strip", mechanics)) {
            loot.add(new ItemStack(block.getType(), 1));
        }
    }

    private List<ItemStack> smelt(List<ItemStack> loot) {
        List<ItemStack> result = new ArrayList<>(loot.size());
        for (ItemStack stack : loot) {
            Material smelted = SMELT.get(stack.getType());
            if (smelted == null) {
                result.add(stack);
            } else {
                ItemStack copy = new ItemStack(smelted, stack.getAmount());
                result.add(copy);
            }
        }
        return result;
    }

    private List<ItemStack> multiply(List<ItemStack> loot, int multiplier) {
        List<ItemStack> result = new ArrayList<>(loot.size());
        for (ItemStack stack : loot) {
            ItemStack copy = stack.clone();
            copy.setAmount(Math.min(copy.getMaxStackSize() * 4, copy.getAmount() * multiplier));
            result.add(copy);
        }
        return result;
    }

    private void deliver(Player player, Location where, List<ItemStack> loot, boolean telekinesis) {
        for (ItemStack stack : loot) {
            if (stack == null || stack.getType().isAir()) continue;
            if (telekinesis) {
                player.getInventory().addItem(stack).values()
                        .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
            } else {
                where.getWorld().dropItemNaturally(where.clone().add(0.5, 0.5, 0.5), stack);
            }
        }
    }

    // ------------------------------------------------------------
    // UTILIDADES
    // ------------------------------------------------------------
    private void replant(Block stump, Material log) {
        Material sapling = derive(log, "_LOG", "_SAPLING");
        if (sapling == null) sapling = derive(log, "_WOOD", "_SAPLING");
        if (sapling == null && log.name().startsWith("MANGROVE")) sapling = Material.MANGROVE_PROPAGULE;
        if (sapling == null && log.name().startsWith("CRIMSON")) sapling = Material.CRIMSON_FUNGUS;
        if (sapling == null && log.name().startsWith("WARPED")) sapling = Material.WARPED_FUNGUS;
        if (sapling == null) return;

        final Material toPlant = sapling;
        final Block target = stump;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!target.getType().isAir()) return;
            Material below = target.getRelative(BlockFace.DOWN).getType();
            boolean soil = below == Material.DIRT || below == Material.GRASS_BLOCK
                    || below == Material.PODZOL || below == Material.COARSE_DIRT
                    || below == Material.ROOTED_DIRT || below == Material.MOSS_BLOCK
                    || below == Material.MUD || below == Material.CRIMSON_NYLIUM
                    || below == Material.WARPED_NYLIUM;
            if (soil) target.setType(toPlant, true);
        }, 2L);
    }

    private void repair(ItemStack tool, int level) {
        if (!(tool.getItemMeta() instanceof Damageable damageable)) return;
        if (!damageable.hasDamage()) return;
        int amount = Math.max(1, 20 * level);
        damageable.setDamage(Math.max(0, damageable.getDamage() - amount));
        tool.setItemMeta(damageable);
    }

    private void reveal(Player player, Block origin, int level) {
        int radius = Math.max(3, Math.min(10, 3 + level * 2));
        int shown = 0;
        for (int x = -radius; x <= radius && shown < 40; x++) {
            for (int y = -radius; y <= radius && shown < 40; y++) {
                for (int z = -radius; z <= radius && shown < 40; z++) {
                    Block block = origin.getRelative(x, y, z);
                    if (!isOre(block.getType())) continue;
                    player.spawnParticle(Particle.HAPPY_VILLAGER,
                            block.getLocation().add(0.5, 0.5, 0.5), 4, 0.2, 0.2, 0.2, 0);
                    shown++;
                }
            }
        }
        if (shown > 0) player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.6f);
    }

    /** Suma una carga y devuelve true cuando el umbral se alcanza (y reinicia). */
    private boolean addCharge(Player player, ItemStack tool, int level) {
        int needed = Math.max(2, (int) Math.round(value(tool, "charge_blast", level)));
        int current = charges.merge(player.getUniqueId(), 1, Integer::sum);
        if (current < needed) {
            player.sendActionBar(net.kyori.adventure.text.Component.text(
                    "\u26A1 " + current + "/" + needed));
            return false;
        }
        charges.put(player.getUniqueId(), 0);
        return true;
    }

    private Material derive(Material source, String from, String to) {
        String name = source.name();
        if (!name.contains(from)) return null;
        return Material.matchMaterial(name.replace(from, to).toLowerCase(Locale.ROOT));
    }
}
