package dev.fce;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HOLOGRAMA DE FUSION — teatro visual para el Drag &amp; Drop.
 *
 * Al soltar un libro sobre un item compatible, frente al jugador aparecen
 * DOS hologramas (ItemDisplay, 1.19.4+): el item objetivo (espada, pico,
 * armadura...) y el libro encantado. Durante el redoble de la forja
 * (ForgeSuspense) ambos giran sobre si mismos, flotan con un vaiven suave
 * y se van ACERCANDO hasta fundirse en un destello blanco justo antes del
 * veredicto. El item del holograma es una copia visual: el real nunca sale
 * del inventario.
 *
 * Sincronizacion: la animacion dura suspense.ticks + un pequeño margen, de
 * modo que el destello de fusion cae justo antes de que ForgeSuspense
 * ejecute la resolucion (exito/fallo), cuyos propios efectos rematan la
 * escena via {@link #burstSuccess(Player)} / {@link #burstFail(Player)}.
 *
 * Seguridad / limpieza (misma filosofia que ForgeSuspense):
 *  - Los displays se crean con setPersistent(false): si el servidor se
 *    cae a mitad de la animacion, desaparecen solos al reiniciar.
 *  - Al desconectarse el jugador o deshabilitarse el plugin, la sesion se
 *    corta y los displays se eliminan de inmediato.
 *  - Una sesion nueva del mismo jugador reemplaza a la anterior.
 *
 * Config (config.yml, todo opcional):
 *   cosmetics.fusion-hologram.enabled: true
 *   cosmetics.fusion-hologram.scale: 0.7      # tamaño de los hologramas
 *   cosmetics.fusion-hologram.distance: 1.8   # distancia frente al jugador
 *
 * Uso: FusionHologram.get(plugin).play(player, targetItem). La instancia
 * se crea y registra sola la primera vez (no hay que tocar onEnable).
 */
public final class FusionHologram implements Listener {

    private static final class Session {
        ItemDisplay item;
        ItemDisplay book;
        Location center;
        Vector right;
        double separation;
        org.bukkit.scheduler.BukkitTask task;
    }

    private static FusionHologram instance;

    private final FabledCustomEnchantsPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();

    private FusionHologram(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Instancia compartida; se registra como listener la primera vez. */
    public static FusionHologram get(FabledCustomEnchantsPlugin plugin) {
        if (instance == null || instance.plugin != plugin) {
            instance = new FusionHologram(plugin);
            Bukkit.getPluginManager().registerEvents(instance, plugin);
        }
        return instance;
    }

    /**
     * Lanza la animacion de fusion frente al jugador. {@code targetItem} se
     * clona: el holograma es pura estetica y jamas toca el item real.
     */
    public void play(Player player, ItemStack targetItem) {
        if (!plugin.getConfig().getBoolean("cosmetics.fusion-hologram.enabled", true)) return;
        stop(player.getUniqueId()); // reemplaza cualquier sesion colgada

        int suspenseTicks = Math.max(10, Math.min(100,
                plugin.getConfig().getInt("suspense.ticks", 30)));
        final int total = suspenseTicks + 4; // el destello cae junto al veredicto
        float scale = (float) plugin.getConfig().getDouble("cosmetics.fusion-hologram.scale", 0.7);
        double distance = plugin.getConfig().getDouble("cosmetics.fusion-hologram.distance", 1.8);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 1);
        dir.normalize();
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());

        ItemStack shown = targetItem.clone();
        shown.setAmount(1);

        Session s = new Session();
        s.center = eye.clone().add(dir.clone().multiply(distance)).add(0, 0.1, 0);
        s.right = right;
        s.separation = 0.85;
        s.item = spawnDisplay(s.center.clone().add(right.clone().multiply(-s.separation)), shown, scale);
        s.book = spawnDisplay(s.center.clone().add(right.clone().multiply(s.separation)),
                new ItemStack(Material.ENCHANTED_BOOK), scale);
        sessions.put(player.getUniqueId(), s);

        player.playSound(s.center, "block.beacon.activate", 0.6f, 1.5f);

        s.task = new BukkitRunnable() {
            int tick = 0;
            float angle = 0f;

            @Override
            public void run() {
                tick++;
                if (tick >= total || !player.isOnline() || s.item == null || !s.item.isValid()) {
                    // DESTELLO DE FUSION: ambos hologramas se funden en uno.
                    if (player.isOnline()) {
                        player.getWorld().spawnParticle(Particle.FLASH, s.center, 1);
                        player.getWorld().spawnParticle(Particle.END_ROD, s.center,
                                24, 0.15, 0.15, 0.15, 0.12);
                        player.playSound(s.center, "block.beacon.power_select", 0.8f, 1.6f);
                    }
                    stop(player.getUniqueId()); // cancela esta task y borra displays
                    return;
                }

                float progress = tick / (float) total;
                angle += 0.28f;

                // Convergencia con vaiven vertical opuesto (uno sube, otro baja)
                double sep = s.separation * (1.0 - progress);
                double bob = Math.sin(tick * 0.35) * 0.06;
                Location a = s.center.clone().add(s.right.clone().multiply(-sep)).add(0, bob, 0);
                Location b = s.center.clone().add(s.right.clone().multiply(sep)).add(0, -bob, 0);
                s.item.teleport(a);
                s.book.teleport(b);

                // Giro sobre si mismos, en sentidos opuestos
                s.item.setTransformation(spin(angle, scale));
                s.book.setTransformation(spin(-angle, scale));

                // Aura magica creciente alrededor de cada holograma
                player.getWorld().spawnParticle(Particle.ENCHANT, a, 4 + (int) (progress * 8),
                        0.15, 0.2, 0.15, 0.5);
                player.getWorld().spawnParticle(Particle.ENCHANT, b, 4 + (int) (progress * 8),
                        0.15, 0.2, 0.15, 0.5);
                player.getWorld().spawnParticle(Particle.PORTAL, s.center, 3,
                        sep * 0.5, 0.1, sep * 0.5, 0.02);

                // Campanilla ascendente cada 8 ticks (acompaña al yunque del suspenso)
                if (tick % 8 == 0) {
                    player.playSound(s.center, "block.amethyst_block.chime",
                            0.7f, 0.8f + progress * 0.8f);
                }

                // Anillo de chispas cuando ya casi se tocan
                if (progress > 0.75 && tick % 3 == 0) {
                    player.getWorld().spawnParticle(Particle.END_ROD, s.center, 6,
                            0.1, 0.1, 0.1, 0.04);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** REMATE DE EXITO: fuegos + destellos + level-up sobre el punto de fusion. */
    public static void burstSuccess(Player player) {
        Location loc = frontOf(player);
        player.getWorld().spawnParticle(Particle.FIREWORK, loc, 40, 0.3, 0.3, 0.3, 0.12);
        player.getWorld().spawnParticle(Particle.END_ROD, loc, 25, 0.25, 0.25, 0.25, 0.08);
        player.getWorld().spawnParticle(Particle.ENCHANTED_HIT, loc, 40, 0.4, 0.5, 0.4, 0.15);
        player.playSound(loc, "entity.player.levelup", 0.9f, 1.5f);
        player.playSound(loc, "block.enchantment_table.use", 1.0f, 1.2f);
    }

    /** REMATE DE FALLO: humo y chispas de lava, la fusion se apaga. */
    public static void burstFail(Player player) {
        Location loc = frontOf(player);
        player.getWorld().spawnParticle(Particle.LARGE_SMOKE, loc, 30, 0.25, 0.3, 0.25, 0.04);
        player.getWorld().spawnParticle(Particle.LAVA, loc, 6, 0.2, 0.2, 0.2, 0.0);
        player.playSound(loc, "block.fire.extinguish", 0.9f, 0.8f);
    }

    /* ==================== internos ==================== */

    private static Location frontOf(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 1);
        return eye.add(dir.normalize().multiply(1.6));
    }

    private ItemDisplay spawnDisplay(Location loc, ItemStack stack, float scale) {
        return loc.getWorld().spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(stack);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setBillboard(Display.Billboard.NONE);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setPersistent(false);   // si el server cae, no queda basura
            d.setGravity(false);
            d.setInvulnerable(true);
            d.setTeleportDuration(1); // interpola los teleports tick a tick
            d.setTransformation(spin(0f, scale));
        });
    }

    private static Transformation spin(float angle, float scale) {
        return new Transformation(
                new Vector3f(),
                new AxisAngle4f(angle, 0f, 1f, 0f),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(0f, 0f, 1f, 0f));
    }

    private void stop(UUID id) {
        Session s = sessions.remove(id);
        if (s == null) return;
        if (s.task != null) s.task.cancel();
        if (s.item != null) s.item.remove();
        if (s.book != null) s.book.remove();
    }

    /** Quit a mitad de la animacion: fuera hologramas, sin residuos. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        stop(e.getPlayer().getUniqueId());
    }

    /** Apagado del plugin: se limpian todas las sesiones activas. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPluginDisable(PluginDisableEvent e) {
        if (e.getPlugin() != plugin) return;
        for (UUID id : new ArrayList<>(sessions.keySet())) stop(id);
        instance = null;
    }
}
