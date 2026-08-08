package dev.fce;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Modo inspeccion (ADMIN).
 *
 * Con el modo activo (/encantos inspect o el boton del menu principal), un
 * click sobre cualquier pieza de equipo abre una GUI con SOLO los
 * encantamientos compatibles con ese item, y permite recibir el libro
 * correspondiente al instante.
 *
 * La inspeccion es DE UN SOLO USO: se consume en el momento en que abre la
 * GUI. Asi, al cerrar el menu y volver a hacer click sobre el mismo item, el
 * inventario se comporta con normalidad en lugar de reabrir el inspector en
 * bucle. Para inspeccionar otro item se reactiva con /encantos inspect (o el
 * boton del menu) o se usa /encantos check con el item en la mano.
 *
 * No interfiere con la mecanica drag &amp; drop: el inspector solo actua cuando
 * el cursor esta vacio, mientras que la aplicacion de libros y los polvos
 * exigen algo en el cursor.
 */
public class AdminInspectListener implements Listener {

    private final FabledCustomEnchantsPlugin plugin;
    private final Set<UUID> inspecting = new HashSet<>();

    public AdminInspectListener(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isInspecting(Player player) {
        return inspecting.contains(player.getUniqueId());
    }

    /** Alterna el modo y avisa al jugador. Devuelve el estado resultante. */
    public boolean toggle(Player player) {
        if (!player.hasPermission("fce.admin")) {
            plugin.messages().send(player, "no-permission");
            return false;
        }
        boolean now;
        if (inspecting.remove(player.getUniqueId())) {
            now = false;
            plugin.messages().send(player, "inspect-off");
        } else {
            inspecting.add(player.getUniqueId());
            now = true;
            plugin.messages().send(player, "inspect-on");
        }
        plugin.messages().playSound(player, "gui-click");
        return now;
    }

    /**
     * Abre el inspector para un material concreto, o avisa si no aplica nada.
     * (Ruta de /encantos check: no consume el modo porque no lo necesita.)
     */
    public void inspect(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            plugin.messages().send(player, "inspect-hand-empty");
            return;
        }
        Material material = item.getType();
        if (plugin.enchants().byMaterial(material).isEmpty()) {
            plugin.messages().send(player, "inspect-none");
            return;
        }
        plugin.menus().openForMaterial(player, material);
    }

    /**
     * Prioridad LOW: corre antes que la aplicacion de libros (HIGH), pero
     * ambos filtran por el estado del cursor, asi que nunca compiten.
     *
     * La GUI no se abre dentro del propio evento (el cliente dibujaria el
     * item duplicado): se cancela el click, se refresca el inventario y la
     * apertura se programa para el tick siguiente. El modo se consume AQUI,
     * antes de abrir, para que el siguiente click sobre un item sea un click
     * normal de inventario.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!isInspecting(player)) return;
        if (!player.hasPermission("fce.admin")) return;
        // Nunca dentro de las GUIs del plugin
        if (event.getInventory().getHolder() instanceof MenuManager.MenuHolder) return;

        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir()) return; // drag & drop manda

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) return;
        Material material = clicked.getType();
        if (plugin.enchants().byMaterial(material).isEmpty()) return;

        event.setCancelled(true);
        // Un solo uso: consumir el modo antes de abrir evita el bucle de reapertura
        inspecting.remove(player.getUniqueId());
        // Deshace cualquier prediccion visual que el cliente ya haya dibujado
        player.updateInventory();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            player.closeInventory();      // cierra el inventario de origen sin dejar residuos
            player.updateInventory();
            plugin.menus().openForMaterial(player, material);
            plugin.messages().send(player, "inspect-used");
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inspecting.remove(event.getPlayer().getUniqueId());
    }
}
