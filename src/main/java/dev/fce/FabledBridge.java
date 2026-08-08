package dev.fce;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import studio.magemonkey.fabled.Fabled;
import studio.magemonkey.fabled.api.player.PlayerData;
import studio.magemonkey.fabled.api.player.PlayerSkill;
import studio.magemonkey.fabled.api.skills.Skill;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Puente con Fabled 5: sincroniza el nivel de cada skill dinamica
 * (FE_Veta, FE_Zeus...) con el MAXIMO nivel del encantamiento presente en el
 * equipo del jugador (mano principal, secundaria y armadura). Asi los
 * triggers de Fabled (PHYSICAL_DAMAGE, TOOK_PHYSICAL_DAMAGE, KILL,
 * BLOCK_BREAK) ejecutan el efecto con el escalado nativo base + scale sin que
 * este plugin duplique logica.
 *
 * La sincronizacion se dispara por eventos (cambio de item en mano, cambio de
 * mano secundaria, entrar al servidor, cerrar un inventario) y ademas por un
 * temporizador de respaldo cada 5 segundos, de modo que ninguna via de
 * equipamiento pueda dejar una skill desactualizada.
 *
 * Si una skill no existe en Fabled (los .yml de fabled-skills/ no se copiaron
 * o falta /fabled reload), se avisa UNA vez por skill en consola con la
 * instruccion exacta para corregirlo: esa es la causa tipica de que un
 * encantamiento aplicado "no haga nada".
 */
public class FabledBridge implements Listener {

    /** Nombres historicos del motor: Fabled, y sus versiones previas. */
    private static final String[] ENGINE_NAMES = {"Fabled", "ProSkillAPI", "SkillAPI"};

    private final FabledCustomEnchantsPlugin plugin;
    private boolean warned;
    private final Set<String> missingSkillWarned = new HashSet<>();

    public FabledBridge(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Comprueba el motor en el momento de usarlo. Un softdepend no garantiza
     * el orden real de habilitacion, de modo que preguntarlo en onEnable puede
     * dar un falso negativo.
     */
    public boolean available() {
        for (String name : ENGINE_NAMES) {
            Plugin engine = Bukkit.getPluginManager().getPlugin(name);
            if (engine != null && engine.isEnabled()) return true;
        }
        return false;
    }

    /**
     * Verificacion diferida: se ejecuta un tick despues de que el servidor
     * termine de cargar todos los plugins. Solo entonces la ausencia del motor
     * es una conclusion fiable.
     */
    public void verifyEngineLater() {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (available()) {
                plugin.getLogger().info("Motor de efectos detectado: los encantamientos estan activos.");
                for (Player online : Bukkit.getOnlinePlayers()) sync(online);
            } else {
                warnOnce();
            }
        });
    }

    /**
     * Respaldo periodico (cada 5 segundos): garantiza que el nivel de la skill
     * acompañe al equipo aunque alguna via de cambio no dispare evento.
     */
    public void startPeriodicSync() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!available()) return;
            for (Player online : Bukkit.getOnlinePlayers()) sync(online);
        }, 100L, 100L);
    }

    private void warnOnce() {
        if (warned) return;
        warned = true;
        plugin.getLogger().info("Fabled no detectado: el motor de efectos NATIVO se encarga "
                + "de todos los encantamientos, asi que el sistema funciona al completo. "
                + "Fabled solo es necesario si quieres reutilizar sus skills.");
    }

    private void warnMissingSkill(EnchantDefinition def) {
        if (!missingSkillWarned.add(def.fabledSkill())) return;
        plugin.getLogger().info("La skill opcional '" + def.fabledSkill() + "' (encantamiento '"
                + def.id() + "') no esta cargada en Fabled. Copia fabled-skills/"
                + def.fabledSkill().toLowerCase() + ".yml a plugins/Fabled/dynamic/skill/ "
                + "y ejecuta /fabled reload (o reinicia). Sin ella, ese encantamiento no hace nada.");
    }

    @EventHandler
    public void onHeldChange(PlayerItemHeldEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> sync(event.getPlayer()));
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> sync(event.getPlayer()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> sync(event.getPlayer()));
    }

    /** Cubre cambios de armadura hechos dentro del inventario. */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> sync(player));
        }
    }

    public void sync(Player player) {
        if (!player.isOnline()) return;
        if (!available()) {
            warnOnce();
            return;
        }
        try {
            PlayerData data = Fabled.getData(player);
            ItemStack[] equipment = {
                    player.getInventory().getItemInMainHand(),
                    player.getInventory().getItemInOffHand(),
                    player.getInventory().getHelmet(),
                    player.getInventory().getChestplate(),
                    player.getInventory().getLeggings(),
                    player.getInventory().getBoots()
            };

            for (EnchantDefinition def : plugin.enchants().all()) {
                if (def.fabledSkill().isEmpty()) continue;

                int desired = 0;
                for (ItemStack item : equipment) {
                    if (item == null || !item.hasItemMeta()) continue;
                    desired = Math.max(desired, item.getItemMeta()
                            .getPersistentDataContainer()
                            .getOrDefault(Keys.enchantOnItem(def.id()), PersistentDataType.INTEGER, 0));
                }

                Skill skill = Fabled.getSkill(def.fabledSkill());
                if (skill == null) {
                    // Solo avisa si el jugador realmente lleva ese encantamiento:
                    // asi el log señala el problema justo cuando importa.
                    if (desired > 0) warnMissingSkill(def);
                    continue;
                }

                PlayerSkill playerSkill = data.getSkill(skill.getName());
                if (playerSkill == null) {
                    if (desired <= 0) continue;
                    data.addSkill(skill, null);
                    playerSkill = data.getSkill(skill.getName());
                    if (playerSkill == null) continue;
                }
                // Sube o baja el nivel con proteccion contra bucles si la API rechaza el cambio
                while (playerSkill.getLevel() < desired) {
                    int before = playerSkill.getLevel();
                    data.forceUpSkill(playerSkill);
                    if (playerSkill.getLevel() == before) break;
                }
                while (playerSkill.getLevel() > desired) {
                    int before = playerSkill.getLevel();
                    data.forceDownSkill(playerSkill);
                    if (playerSkill.getLevel() == before) break;
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo sincronizar skills de Fabled: " + t.getMessage());
        }
    }

    /**
     * Informe de diagnostico para /encantos debug: estado del motor, skills
     * cargadas frente a definidas, y el detalle del item en mano.
     */
    public List<String> debugReport(Player player) {
        List<String> lines = new ArrayList<>();
        lines.add("§8— §dFabledCustomEnchants §7· diagnóstico §8—");
        lines.add("§7Motor de efectos: " + (available() ? "§adetectado" : "§cNO detectado"));
        if (!available()) {
            lines.add("§7Instala Fabled en plugins/ y reinicia. Sin él, los efectos no se ejecutan.");
            return lines;
        }
        try {
            int ok = 0;
            List<String> missing = new ArrayList<>();
            for (EnchantDefinition def : plugin.enchants().all()) {
                if (def.fabledSkill().isEmpty()) continue;
                if (Fabled.getSkill(def.fabledSkill()) == null) missing.add(def.fabledSkill());
                else ok++;
            }
            lines.add("§7Skills cargadas en Fabled: §f" + ok + "§7/§f" + (ok + missing.size()));
            if (!missing.isEmpty()) {
                lines.add("§cFaltan§7 (copia fabled-skills/ a plugins/Fabled/dynamic/skill/ y /fabled reload):");
                lines.add("§8" + String.join(", ", missing));
            }

            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.hasItemMeta()) {
                PlayerData data = Fabled.getData(player);
                boolean any = false;
                for (EnchantDefinition def : plugin.enchants().all()) {
                    int lvl = hand.getItemMeta().getPersistentDataContainer()
                            .getOrDefault(Keys.enchantOnItem(def.id()), PersistentDataType.INTEGER, 0);
                    if (lvl <= 0) continue;
                    any = true;
                    Skill skill = Fabled.getSkill(def.fabledSkill());
                    PlayerSkill ps = skill == null ? null : data.getSkill(skill.getName());
                    lines.add("§7En mano: §f" + def.displayName() + " " + BookFactory.roman(lvl)
                            + " §7→ skill " + (skill == null ? "§cno cargada"
                            : ps == null ? "§esin registrar aún" : "§anivel " + ps.getLevel()));
                }
                if (!any) lines.add("§7El ítem en mano no tiene encantamientos del sistema.");
            } else {
                lines.add("§7Sostén un ítem encantado para ver su estado.");
            }
        } catch (Throwable t) {
            lines.add("§cError al generar el diagnóstico: §7" + t.getMessage());
        }
        return lines;
    }
}
