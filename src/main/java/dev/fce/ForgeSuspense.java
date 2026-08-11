package dev.fce;

import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SUSPENSO DE FORJA — la anticipacion es donde vive la dopamina.
 *
 * En vez de resolver la aplicacion/fusion al instante, mete ~1.5 segundos de
 * tension: sonido de yunque acelerando (pitch creciente) y particulas de
 * encantamiento en aumento... y ENTONCES el veredicto.
 *
 * Seguridad durante el suspenso:
 *  - El inventario del jugador queda BLOQUEADO (clicks, drags, soltar con Q
 *    y swap de mano F cancelados) para que el item objetivo no pueda moverse
 *    mientras la tirada esta "en el aire". Sin esto, un jugador podria salvar
 *    su item de la ruptura escondiendolo a mitad del redoble.
 *  - Si el jugador se desconecta, la resolucion se ejecuta INMEDIATAMENTE
 *    (antes de que se guarde su inventario): nada se pierde ni se duplica.
 *  - En onDisable() el plugin llama resolveAll() por el mismo motivo.
 *
 * Config (config.yml, todo opcional):
 *   suspense.enabled: true      # apagar para volver al resultado instantaneo
 *   suspense.ticks: 30          # duracion del redoble (10-100 ticks)
 *   suspense.on-apply: true     # suspenso al aplicar libros
 *   suspense.on-fusion: true    # suspenso en la Forja de Fusion
 *
 * Los casos con exito efectivo del 100% se resuelven al instante (no hay
 * tension posible y frenaria la aplicacion en masa de libros garantizados).
 */
public final class ForgeSuspense implements Listener {

    private static final class Pending {
        BukkitTask task;
        Runnable resolution;
    }

    private final FabledCustomEnchantsPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public ForgeSuspense(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("suspense.enabled", true);
    }

    /** ¿Este jugador tiene una tirada pendiente de revelar? */
    public boolean busy(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    /**
     * Inicia el redoble y ejecuta {@code resolution} al terminar.
     * La resolucion corre SIEMPRE exactamente una vez (al final del redoble,
     * al desconectarse el jugador o en resolveAll()).
     */
    public void begin(Player player, Runnable resolution) {
        UUID id = player.getUniqueId();
        if (pending.containsKey(id)) { // no deberia pasar (clicks bloqueados)
            resolution.run();
            return;
        }
        int ticks = Math.max(10, Math.min(100, plugin.getConfig().getInt("suspense.ticks", 30)));
        int interval = 8;
        int beats = Math.max(1, ticks / interval);

        Pending p = new Pending();
        p.resolution = resolution;
        pending.put(id, p);

        p.task = new BukkitRunnable() {
            int beat = 0;

            @Override
            public void run() {
                beat++;
                if (beat <= beats && player.isOnline()) {
                    // Yunque acelerando: pitch creciente en cada golpe.
                    float pitch = 0.6f + (0.8f * beat / beats);
                    player.playSound(player.getLocation(), "block.anvil.use", 0.7f, pitch);
                    player.getWorld().spawnParticle(Particle.ENCHANT,
                            player.getLocation().add(0, 1.2, 0),
                            12 * beat, 0.4, 0.5, 0.4, 0.6);
                } else {
                    finish(id);
                }
            }
        }.runTaskTimer(plugin, interval, interval);
    }

    private void finish(UUID id) {
        Pending p = pending.remove(id);
        if (p == null) return;
        if (p.task != null) p.task.cancel();
        p.resolution.run();
    }

    /** Resuelve al instante todo lo pendiente (llamar en onDisable). */
    public void resolveAll() {
        for (UUID id : new ArrayList<>(pending.keySet())) finish(id);
    }

    /* ============ Bloqueo del inventario durante el suspenso ============ */

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player player && busy(player)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player player && busy(player)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (busy(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        if (busy(e.getPlayer())) e.setCancelled(true);
    }

    /** Quit a mitad del redoble: se resuelve YA, antes de guardar el inventario. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        finish(e.getPlayer().getUniqueId());
    }
}
