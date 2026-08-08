package dev.fce;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MOTOR DE EFECTOS NATIVO.
 *
 * Ejecuta los efectos declarados en enchants/&lt;id&gt;.yml (bloque effects:)
 * leyendo el nivel directamente de los Data Components del equipo. No depende
 * de Fabled ni de niveles de skill, de modo que un encantamiento aplicado
 * SIEMPRE surte efecto: si el item lo lleva, el efecto se dispara.
 *
 * Disparadores:
 *   attack -> al golpear (arma en mano)
 *   defend -> al recibir daño (armadura y manos)
 *   kill   -> al matar (arma en mano)
 *   mine   -> al romper un bloque (herramienta en mano)
 *   land   -> al caer (botas y armadura)
 */
public class EffectEngine implements Listener {

    public static final String ATTACK = "attack";
    public static final String DEFEND = "defend";
    public static final String KILL = "kill";
    public static final String MINE = "mine";
    public static final String LAND = "land";

    private final FabledCustomEnchantsPlugin plugin;

    public EffectEngine(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------
    // DISPARADORES
    // ------------------------------------------------------------
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        Player attacker = resolveAttacker(event);
        if (attacker != null && !attacker.equals(victim)) {
            runHand(attacker, victim, ATTACK);
        }
        if (victim instanceof Player defender) {
            runEquipment(defender, attacker, DEFEND);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (event.getEntity() instanceof Player player) {
            runEquipment(player, null, LAND);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) runHand(killer, event.getEntity(), KILL);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        runHand(event.getPlayer(), null, MINE);
    }

    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    // ------------------------------------------------------------
    // RESOLUCION DE NIVELES
    // ------------------------------------------------------------
    /** Solo la mano principal: armas y herramientas. */
    private void runHand(Player player, LivingEntity victim, String trigger) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) return;
        for (Map.Entry<EnchantDefinition, Integer> entry : plugin.enchants().onItem(hand).entrySet()) {
            trigger(player, victim, entry.getKey(), entry.getValue(), trigger);
        }
    }

    /** Armadura + ambas manos, tomando el nivel mas alto de cada encantamiento. */
    private void runEquipment(Player player, LivingEntity victim, String trigger) {
        ItemStack[] equipment = {
                player.getInventory().getItemInMainHand(),
                player.getInventory().getItemInOffHand(),
                player.getInventory().getHelmet(),
                player.getInventory().getChestplate(),
                player.getInventory().getLeggings(),
                player.getInventory().getBoots()
        };
        Map<EnchantDefinition, Integer> best = new LinkedHashMap<>();
        for (ItemStack item : equipment) {
            if (item == null || item.getType().isAir()) continue;
            plugin.enchants().onItem(item).forEach((def, level) ->
                    best.merge(def, level, Math::max));
        }
        best.forEach((def, level) -> trigger(player, victim, def, level, trigger));
    }

    // ------------------------------------------------------------
    // EJECUCION
    // ------------------------------------------------------------
    private void trigger(Player player, LivingEntity victim,
                         EnchantDefinition def, int level, String trigger) {
        if (!def.hasEffects()) return;
        EnchantDefinition.Effects effects = def.effects();
        if (!effects.trigger().equalsIgnoreCase(trigger)) return;

        double chance = effects.chanceAt(level);
        if (chance < 100 && ThreadLocalRandom.current().nextDouble(100) >= chance) return;

        for (EnchantDefinition.Action action : effects.actions()) {
            LivingEntity target = action.onVictim() ? victim : player;
            if (target == null || target.isDead()) continue;
            apply(player, target, action, level);
        }
    }

    private void apply(Player source, LivingEntity target,
                       EnchantDefinition.Action action, int level) {
        switch (action.type()) {
            case "potion" -> {
                PotionEffectType type = potion(action.value());
                if (type == null) return;
                int ticks = (int) Math.round(Math.max(0.5, action.secondsAt(level)) * 20);
                target.addPotionEffect(new PotionEffect(type, ticks,
                        Math.max(0, action.amplifier()), false, true, true));
            }
            case "heal" -> target.setHealth(Math.min(maxHealth(target),
                    target.getHealth() + action.amountAt(level)));
            case "damage" -> {
                double amount = action.amountAt(level);
                if (amount <= 0) return;
                if (action.trueDamage()) {
                    // Daño real: ignora armadura y encantamientos de proteccion
                    target.setHealth(Math.max(0, target.getHealth() - amount));
                } else {
                    target.damage(amount, source);
                }
            }
            case "fire" -> target.setFireTicks((int) Math.round(action.secondsAt(level) * 20));
            case "lightning" -> target.getWorld().strikeLightning(target.getLocation());
            case "push" -> {
                Vector away = target.getLocation().toVector()
                        .subtract(source.getLocation().toVector());
                if (away.lengthSquared() < 0.01) away = source.getLocation().getDirection();
                target.setVelocity(away.normalize()
                        .multiply(Math.max(0.2, action.amountAt(level) * 0.25))
                        .setY(0.4));
            }
            case "sound" -> {
                Sound sound = sound(action.value());
                if (sound == null) return;
                target.getWorld().playSound(target.getLocation(), sound,
                        (float) action.volume(), (float) action.pitch());
            }
            case "particle" -> {
                Particle particle = particle(action.value());
                if (particle == null) return;
                Location at = target.getLocation().add(0, 1, 0);
                target.getWorld().spawnParticle(particle, at,
                        Math.max(1, action.count()), 0.4, 0.5, 0.4, 0.05);
            }
            default -> {
            }
        }
    }

    /**
     * Vida maxima sin depender del enum Attribute, cuyo nombre cambio entre
     * versiones (GENERIC_MAX_HEALTH -> MAX_HEALTH). El metodo de la entidad
     * es estable en todo el rango 1.21 -> 26.x.
     */
    @SuppressWarnings("deprecation")
    private static double maxHealth(LivingEntity target) {
        return target.getMaxHealth();
    }

    // ------------------------------------------------------------
    // RESOLUCION TOLERANTE DE NOMBRES (1.21 -> 26.x)
    // ------------------------------------------------------------
    @SuppressWarnings("deprecation")
    private PotionEffectType potion(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        PotionEffectType type = PotionEffectType.getByName(key);
        if (type != null) return type;
        // Alias historicos por si el YAML usa nombres antiguos
        return switch (key) {
            case "SPEED", "SWIFTNESS" -> PotionEffectType.SPEED;
            case "SLOWNESS", "SLOW" -> PotionEffectType.SLOWNESS;
            case "HASTE", "FAST_DIGGING" -> PotionEffectType.HASTE;
            case "MINING_FATIGUE", "SLOW_DIGGING" -> PotionEffectType.MINING_FATIGUE;
            case "STRENGTH", "INCREASE_DAMAGE" -> PotionEffectType.STRENGTH;
            case "RESISTANCE", "DAMAGE_RESISTANCE" -> PotionEffectType.RESISTANCE;
            case "REGENERATION", "REGEN" -> PotionEffectType.REGENERATION;
            case "NIGHT_VISION" -> PotionEffectType.NIGHT_VISION;
            case "ABSORPTION" -> PotionEffectType.ABSORPTION;
            case "INVISIBILITY" -> PotionEffectType.INVISIBILITY;
            case "BLINDNESS" -> PotionEffectType.BLINDNESS;
            case "WEAKNESS" -> PotionEffectType.WEAKNESS;
            case "POISON" -> PotionEffectType.POISON;
            case "JUMP_BOOST", "JUMP" -> PotionEffectType.JUMP_BOOST;
            case "FIRE_RESISTANCE" -> PotionEffectType.FIRE_RESISTANCE;
            case "WATER_BREATHING" -> PotionEffectType.WATER_BREATHING;
            default -> null;
        };
    }

    private Sound sound(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return Sound.valueOf(name.trim().toUpperCase(Locale.ROOT).replace('.', '_'));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Particle particle(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.trim().toUpperCase(Locale.ROOT);
        try {
            return Particle.valueOf(key);
        } catch (IllegalArgumentException ex) {
            return switch (key) {
                case "EXPLODE", "EXPLOSION_NORMAL" -> Particle.EXPLOSION;
                case "ENCHANTMENTTABLE", "ENCHANT" -> Particle.ENCHANT;
                case "SMOKELARGE" -> Particle.LARGE_SMOKE;
                case "CLOUDPUFF" -> Particle.CLOUD;
                case "CRIT" -> Particle.CRIT;
                case "DUST" -> Particle.CRIT;
                case "WITCH" -> Particle.WITCH;
                case "HEART" -> Particle.HEART;
                case "FLAME" -> Particle.FLAME;
                case "PORTAL" -> Particle.PORTAL;
                default -> null;
            };
        }
    }
}
