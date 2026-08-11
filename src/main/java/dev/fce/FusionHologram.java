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
import java.util.concurrent.ThreadLocalRandom;

/**
 * HOLOGRAMA DE FUSION — teatro visual para el Drag &amp; Drop.
 *
 * CICLO DE VIDA (sincronizado con el veredicto, no con un temporizador):
 *
 *   1. CONVERGE  — el item objetivo y el libro aparecen frente al jugador y
 *                  ORBITAN en espiral uno alrededor del otro mientras se
 *                  acercan (aceleracion suave, vaiven vertical, giro propio
 *                  cada vez mas rapido, particulas en aumento).
 *   2. HOLD      — al tocarse se FUNDEN: destello, el libro desaparece
 *                  \"absorbido\" y el item queda flotando en el centro,
 *                  girando rapido con un vortice de particulas y un pulso
 *                  de escala. El holograma NO se borra: espera el veredicto.
 *   3. SUCCESS / FAIL — cuando DragAndDropListener resuelve la tirada
 *                  (aplica / no aplica), el holograma remata la escena:
 *                  · exito: el item asciende girando entre fuegos y destellos
 *                    y se desvanece al final.
 *                  · fallo: el item TIEMBLA, se hunde entre humo y chispas de
 *                    lava y se apaga.
 *                  Solo al terminar ese remate se eliminan los displays.
 *
 * Entrada del veredicto: burstSuccess(player) / burstFail(player) — los
 * mismos metodos que ya llamaban DragAndDropListener; si hay una sesion
 * activa, disparan la fase final SOBRE el holograma (y su limpieza); si no
 * la hay (exito 100%, suspenso apagado, holograma deshabilitado), caen al
 * modo clasico: particulas frente al jugador, sin displays.
 *
 * Seguridad / limpieza (misma filosofia que ForgeSuspense):
 *  - setPersistent(false): si el server cae a mitad de animacion, los
 *    displays desaparecen solos al reiniciar.
 *  - Quit del jugador o disable del plugin -> limpieza inmediata.
 *  - Una sesion nueva del mismo jugador reemplaza a la anterior.
 *  - Cortafuegos: si el veredicto no llegara (no deberia pasar), la fase
 *    HOLD se autodestruye a los 4 segundos.
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

    /** Fases de la escena. El veredicto mueve HOLD -> SUCCESS/FAIL. */
    private enum Phase { CONVERGE, HOLD, SUCCESS, FAIL }

    private static final int VERDICT_TICKS = 16;  // duracion del remate
    private static final int HOLD_TIMEOUT = 80;   // cortafuegos (4s)

    private static final class Session {
        ItemDisplay item;
        ItemDisplay book;
        Location center;
        Vector right;
        Vector forward;
        double separation;
        float scale;
        Phase phase = Phase.CONVERGE;
        int phaseTick;
        float spin;
        float orbit;
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
        final int converge = suspenseTicks + 2; // la fusion cae justo antes del veredicto
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
        s.forward = dir.clone();
        s.separation = 0.85;
        s.scale = scale;
        s.item = spawnDisplay(s.center.clone().add(right.clone().multiply(-s.separation)), shown, scale);
        s.book = spawnDisplay(s.center.clone().add(right.clone().multiply(s.separation)),
                new ItemStack(Material.ENCHANTED_BOOK), scale);
        sessions.put(player.getUniqueId(), s);

        player.playSound(s.center, "block.beacon.activate", 0.6f, 1.5f);

        s.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || s.item == null || !s.item.isValid()) {
                    stop(player.getUniqueId());
                    return;
                }
                s.phaseTick++;
                switch (s.phase) {
                    case CONVERGE -> tickConverge(player, s, converge);
                    case HOLD -> tickHold(player, s);
                    case SUCCESS -> tickSuccess(player, s);
                    case FAIL -> tickFail(player, s);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /* ==================== FASE 1: convergencia en espiral ==================== */

    private void tickConverge(Player player, Session s, int converge) {
        float progress = Math.min(1f, s.phaseTick / (float) converge);
        // smoothstep: arranque suave, atraccion final acelerada (mas fisico)
        float eased = progress * progress * (3f - 2f * progress);

        // Orbita en espiral: giran uno alrededor del otro cada vez mas rapido
        s.orbit += 0.10f + 0.22f * eased;
        s.spin += 0.24f + 0.5f * eased;

        double sep = s.separation * (1.0 - eased);
        double bob = Math.sin(s.phaseTick * 0.35) * 0.06 * (1.0 - eased * 0.5);

        Vector radial = s.right.clone().multiply(Math.cos(s.orbit))
                .add(s.forward.clone().multiply(Math.sin(s.orbit) * 0.55)); // elipse: mas ancho que profundo
        Location a = s.center.clone().add(radial.clone().multiply(-sep)).add(0, bob, 0);
        Location b = s.center.clone().add(radial.clone().multiply(sep)).add(0, -bob, 0);
        s.item.teleport(a);
        if (s.book != null) s.book.teleport(b);

        // Latido de escala conforme se acercan (la energia \"aprieta\")
        float pulse = 1f + (float) Math.sin(s.phaseTick * 0.45) * 0.05f * eased;
        s.item.setTransformation(spin(s.spin, s.scale * pulse));
        if (s.book != null) s.book.setTransformation(spin(-s.spin, s.scale * pulse));

        // Aura creciente + estela del arco de la orbita
        player.getWorld().spawnParticle(Particle.ENCHANT, a, 3 + (int) (eased * 7),
                0.12, 0.18, 0.12, 0.5);
        player.getWorld().spawnParticle(Particle.ENCHANT, b, 3 + (int) (eased * 7),
                0.12, 0.18, 0.12, 0.5);
        player.getWorld().spawnParticle(Particle.PORTAL, s.center, 3,
                sep * 0.5, 0.1, sep * 0.5, 0.02);
        if (s.phaseTick % 2 == 0) {
            player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, a, 1, 0.05, 0.05, 0.05, 0.01);
            player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, b, 1, 0.05, 0.05, 0.05, 0.01);
        }

        // Campanilla ascendente cada 8 ticks (acompaña al yunque del suspenso)
        if (s.phaseTick % 8 == 0) {
            player.playSound(s.center, "block.amethyst_block.chime",
                    0.7f, 0.8f + eased * 0.8f);
        }
        // Anillo de chispas cuando ya casi se tocan
        if (eased > 0.75f && s.phaseTick % 3 == 0) {
            player.getWorld().spawnParticle(Particle.END_ROD, s.center, 6,
                    0.1, 0.1, 0.1, 0.04);
        }

        if (s.phaseTick >= converge) merge(player, s);
    }

    /** El libro es absorbido por el item: destello y paso a HOLD. */
    private void merge(Player player, Session s) {
        player.getWorld().spawnParticle(Particle.FLASH, s.center, 1);
        player.getWorld().spawnParticle(Particle.END_ROD, s.center, 24, 0.15, 0.15, 0.15, 0.12);
        player.playSound(s.center, "block.respawn_anchor.charge", 0.8f, 1.4f);
        if (s.book != null) {
            s.book.remove();
            s.book = null;
        }
        s.item.teleport(s.center);
        s.phase = Phase.HOLD;
        s.phaseTick = 0;
    }

    /* ============ FASE 2: fundidos, esperando el veredicto ============ */

    private void tickHold(Player player, Session s) {
        s.spin += 0.85f; // giro frenetico: la energia esta contenida
        float pulse = 1f + (float) Math.sin(s.phaseTick * 0.55) * 0.12f;
        s.item.setTransformation(spin(s.spin, s.scale * pulse));

        // Vortice: el portal converge y las chispas escapan
        player.getWorld().spawnParticle(Particle.PORTAL, s.center, 6, 0.25, 0.25, 0.25, 0.06);
        player.getWorld().spawnParticle(Particle.ENCHANT, s.center, 8, 0.2, 0.25, 0.2, 0.7);
        if (s.phaseTick % 4 == 0) {
            player.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, s.center, 3, 0.15, 0.15, 0.15, 0.05);
        }
        if (s.phaseTick % 10 == 0) {
            player.playSound(s.center, "block.beacon.ambient", 0.5f, 1.8f);
        }

        // Cortafuegos: el veredicto siempre llega en unos pocos ticks;
        // si algo lo impidiera, la escena no queda flotando para siempre.
        if (s.phaseTick > HOLD_TIMEOUT) {
            player.getWorld().spawnParticle(Particle.FLASH, s.center, 1);
            stop(player.getUniqueId());
        }
    }

    /* ============ FASE 3a: VEREDICTO — aplica (exito) ============ */

    private void tickSuccess(Player player, Session s) {
        float p = s.phaseTick / (float) VERDICT_TICKS;

        if (s.phaseTick == 1) {
            player.getWorld().spawnParticle(Particle.FIREWORK, s.center, 40, 0.3, 0.3, 0.3, 0.12);
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, s.center, 30, 0.25, 0.25, 0.25, 0.25);
            player.getWorld().spawnParticle(Particle.END_ROD, s.center, 25, 0.25, 0.25, 0.25, 0.08);
            player.getWorld().spawnParticle(Particle.ENCHANTED_HIT, s.center, 40, 0.4, 0.5, 0.4, 0.15);
            player.playSound(s.center, "entity.player.levelup", 0.9f, 1.5f);
            player.playSound(s.center, "block.enchantment_table.use", 1.0f, 1.2f);
        }

        // Asciende girando, encogiendose al desvanecerse (absorbido por el exito)
        s.spin += 1.1f;
        Location loc = s.center.clone().add(0, p * 0.9, 0);
        s.item.teleport(loc);
        s.item.setTransformation(spin(s.spin, s.scale * (1f - p * 0.65f)));
        player.getWorld().spawnParticle(Particle.END_ROD, loc, 4, 0.08, 0.08, 0.08, 0.02);
        if (s.phaseTick % 3 == 0) {
            player.getWorld().spawnParticle(Particle.FIREWORK, loc, 3, 0.1, 0.1, 0.1, 0.05);
        }

        if (s.phaseTick >= VERDICT_TICKS) {
            player.getWorld().spawnParticle(Particle.FLASH, loc, 1);
            stop(player.getUniqueId());
        }
    }

    /* ============ FASE 3b: VEREDICTO — no aplica (fallo) ============ */

    private void tickFail(Player player, Session s) {
        float p = s.phaseTick / (float) VERDICT_TICKS;

        if (s.phaseTick == 1) {
            player.getWorld().spawnParticle(Particle.LARGE_SMOKE, s.center, 30, 0.25, 0.3, 0.25, 0.04);
            player.getWorld().spawnParticle(Particle.LAVA, s.center, 6, 0.2, 0.2, 0.2, 0.0);
            player.playSound(s.center, "block.fire.extinguish", 0.9f, 0.8f);
            player.playSound(s.center, "entity.item.break", 0.8f, 0.7f);
        }

        // TIEMBLA (sacudida creciente) mientras se hunde entre el humo
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double shake = 0.03 + 0.06 * p;
        Location loc = s.center.clone().add(
                rnd.nextDouble(-shake, shake),
                -p * 0.5 + rnd.nextDouble(-shake, shake),
                rnd.nextDouble(-shake, shake));
        s.spin += 0.15f; // el giro muere: la energia se apago
        s.item.teleport(loc);
        s.item.setTransformation(spin(s.spin, s.scale * (1f - p * 0.5f)));

        player.getWorld().spawnParticle(Particle.LARGE_SMOKE, loc, 2, 0.08, 0.08, 0.08, 0.01);
        if (s.phaseTick % 4 == 0) {
            player.getWorld().spawnParticle(Particle.LAVA, loc, 1, 0.05, 0.05, 0.05, 0.0);
        }

        if (s.phaseTick >= VERDICT_TICKS) {
            player.getWorld().spawnParticle(Particle.ITEM, loc, 12, 0.15, 0.15, 0.15, 0.06,
                    s.item.getItemStack());
            stop(player.getUniqueId());
        }
    }

    /* ==================== ENTRADA DEL VEREDICTO ==================== */

    /**
     * Cambia la sesion activa a su fase final. Si el veredicto llegara antes
     * de terminar la convergencia (redoble mas corto que la animacion), la
     * fusion se adelanta para que el remate ocurra siempre sobre el item ya
     * fundido. Devuelve false si no hay sesion (modo clasico).
     */
    private boolean beginVerdict(Player player, boolean success) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.item == null || !s.item.isValid()) return false;
        if (s.phase == Phase.SUCCESS || s.phase == Phase.FAIL) return true; // ya rematando
        if (s.book != null) merge(player, s); // veredicto adelantado
        s.phase = success ? Phase.SUCCESS : Phase.FAIL;
        s.phaseTick = 0;
        return true;
    }

    /**
     * REMATE DE EXITO (\"aplica\"). Si hay holograma en curso, el remate corre
     * sobre el (asciende entre fuegos y SOLO entonces se elimina); si no,
     * particulas clasicas frente al jugador.
     */
    public static void burstSuccess(Player player) {
        if (instance != null && instance.beginVerdict(player, true)) return;
        Location loc = frontOf(player);
        player.getWorld().spawnParticle(Particle.FIREWORK, loc, 40, 0.3, 0.3, 0.3, 0.12);
        player.getWorld().spawnParticle(Particle.END_ROD, loc, 25, 0.25, 0.25, 0.25, 0.08);
        player.getWorld().spawnParticle(Particle.ENCHANTED_HIT, loc, 40, 0.4, 0.5, 0.4, 0.15);
        player.playSound(loc, "entity.player.levelup", 0.9f, 1.5f);
        player.playSound(loc, "block.enchantment_table.use", 1.0f, 1.2f);
    }

    /**
     * REMATE DE FALLO (\"no aplica\"). Si hay holograma en curso, tiembla y se
     * hunde entre humo antes de eliminarse; si no, humo clasico.
     */
    public static void burstFail(Player player) {
        if (instance != null && instance.beginVerdict(player, false)) return;
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
            // Billboard.FIXED = orientacion fija (no sigue la camara).
            // OJO: el enum Billboard NO tiene NONE; sus valores son
            // FIXED, VERTICAL, HORIZONTAL y CENTER.
            d.setBillboard(Display.Billboard.FIXED);
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
