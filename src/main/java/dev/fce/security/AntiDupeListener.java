package dev.fce.security;

import dev.fce.Keys;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Módulo antidupe de FabledCustomEnchants.
 *
 * 1) Sella cada Libro de Encantamiento (fe_id) con un UID único en su
 *    PersistentDataContainer. Un ítem duplicado por un mod lleva el mismo
 *    UID que el original, y eso lo delata.
 * 2) Barrido periódico y en eventos clave: si el mismo UID aparece dos
 *    veces (en un inventario o en dos jugadores distintos), el clon se
 *    elimina y se avisa a consola y a los admins (fce.admin).
 * 3) Bloquea meter ítems del plugin (libros fe_id y polvos/esencias fd_id)
 *    en GUIs de comercio: villagers y cualquier GUI cuyo título contenga
 *    palabras de trade. Ahí es donde entran los exploits de
 *    desincronización de inventario.
 * 4) Fuerza updateInventory() al cerrar esas GUIs para matar ítems
 *    fantasma del lado del cliente.
 *
 * Registro (en FabledCustomEnchantsPlugin.onEnable()):
 *     dev.fce.security.AntiDupeListener.register(this);
 */
public final class AntiDupeListener implements Listener {

    /** Títulos de GUI (en minúsculas) que se consideran de comercio. */
    private static final Set<String> TRADE_KEYWORDS = Set.of(
            "trade", "trueque", "intercambio", "comercio"
    );

    private final Plugin plugin;
    private final NamespacedKey keyUid;

    public AntiDupeListener(Plugin plugin) {
        this.plugin = plugin;
        this.keyUid = Keys.of("fce_uid");
    }

    /** Registra el listener y el barrido periódico (cada 5 segundos). */
    public static void register(Plugin plugin) {
        AntiDupeListener listener = new AntiDupeListener(plugin);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, listener::sweepAllPlayers, 100L, 100L);
    }

    /* ==================== Identificación de ítems ==================== */

    /** ¿Es un ítem del plugin? (libro fe_id o polvo/esencia fd_id, en cualquier namespace) */
    private boolean isFceItem(ItemStack item) {
        return hasDataKey(item, "fe_id") || hasDataKey(item, "fd_id");
    }

    /** ¿Es un Libro de Encantamiento? Solo estos se sellan con UID (stack-size 1). */
    private boolean isBook(ItemStack item) {
        return hasDataKey(item, "fe_id");
    }

    private boolean hasDataKey(ItemStack item, String key) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        for (NamespacedKey k : pdc.getKeys()) {
            if (k.getKey().equals(key)) return true;
        }
        return false;
    }

    /* ==================== Sellado con UID ==================== */

    /** Añade el UID al libro si aún no lo tiene. */
    private void stamp(ItemStack item) {
        if (!isBook(item)) return;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (!pdc.has(keyUid, PersistentDataType.STRING)) {
            pdc.set(keyUid, PersistentDataType.STRING, UUID.randomUUID().toString());
            item.setItemMeta(meta);
        }
    }

    private String uidOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer()
                .get(keyUid, PersistentDataType.STRING);
    }

    /* ==================== Barrido anticlones ==================== */

    /** Recorre a todos los jugadores online buscando UIDs repetidos. */
    private void sweepAllPlayers() {
        Map<String, Player> seen = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            sweepPlayer(player, seen);
        }
    }

    /** Sella y barre el inventario de un jugador contra el mapa global de UIDs vistos. */
    private void sweepPlayer(Player owner, Map<String, Player> seen) {
        ItemStack[] contents = owner.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null) continue;
            stamp(item);
            String uid = uidOf(item);
            if (uid == null) continue;
            Player first = seen.putIfAbsent(uid, owner);
            if (first != null) {
                owner.getInventory().setItem(i, null);
                alert(owner, first, item);
            }
        }
    }

    /** Barre un inventario superior (cofre, GUI) junto al del jugador que lo abre. */
    private void sweepView(Player player, Inventory top) {
        Map<String, Player> seen = new HashMap<>();
        for (int i = 0; i < top.getSize(); i++) {
            ItemStack item = top.getItem(i);
            if (item == null) continue;
            stamp(item);
            String uid = uidOf(item);
            if (uid == null) continue;
            if (seen.putIfAbsent(uid, player) != null) {
                top.setItem(i, null);
                alert(player, player, item);
            }
        }
        sweepPlayer(player, seen);
    }

    private void alert(Player holder, Player original, ItemStack clone) {
        String name = clone.getType().name();
        if (clone.hasItemMeta() && clone.getItemMeta().hasDisplayName()) {
            name = ChatColor.stripColor(clone.getItemMeta().getDisplayName());
        }
        String msg = ChatColor.RED + "[FCE-AntiDupe] " + ChatColor.GRAY
                + "Ítem clonado eliminado: " + ChatColor.WHITE + name
                + ChatColor.GRAY + " | portador: " + ChatColor.WHITE + holder.getName()
                + ChatColor.GRAY + " | original en: " + ChatColor.WHITE + original.getName();
        Bukkit.getConsoleSender().sendMessage(msg);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("fce.admin")) p.sendMessage(msg);
        }
    }

    /* ==================== Detección de GUIs de comercio ==================== */

    private boolean isTradeGui(InventoryView view) {
        if (view.getTopInventory().getType() == InventoryType.MERCHANT) return true;
        String title = ChatColor.stripColor(view.getTitle()).toLowerCase(Locale.ROOT);
        for (String kw : TRADE_KEYWORDS) {
            if (title.contains(kw)) return true;
        }
        return false;
    }

    private void deny(Player player) {
        player.sendMessage(ChatColor.RED + "No puedes comerciar libros, polvos ni esencias por este medio.");
        player.updateInventory();
    }

    /* ==================== Eventos ==================== */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> sweepPlayer(e.getPlayer(), new HashMap<>()), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player) {
            stamp(e.getItem().getItemStack());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player player) {
            sweepView(player, e.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;

        stamp(e.getCurrentItem());

        if (!isTradeGui(e.getView())) return;

        Inventory top = e.getView().getTopInventory();
        boolean clickedTop = e.getClickedInventory() != null
                && e.getClickedInventory().equals(top);

        ItemStack hotbarItem = e.getHotbarButton() >= 0
                ? player.getInventory().getItem(e.getHotbarButton())
                : null;

        boolean blocked =
                // Colocar desde el cursor o swap con hotbar dentro de la GUI de trade
                (clickedTop && (isFceItem(e.getCursor()) || isFceItem(hotbarItem)))
                // Shift-click desde el inventario propio hacia la GUI de trade
                || (!clickedTop
                        && e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                        && isFceItem(e.getCurrentItem()))
                // Doble clic (COLLECT_TO_CURSOR) recolectando ítems FCE
                || (e.getClick() == ClickType.DOUBLE_CLICK && isFceItem(e.getCursor()))
                // Sacar un ítem FCE que ya esté dentro de la GUI de trade
                || (clickedTop && isFceItem(e.getCurrentItem()));

        if (blocked) {
            e.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!isTradeGui(e.getView())) return;
        if (!isFceItem(e.getOldCursor())) return;

        int topSize = e.getView().getTopInventory().getSize();
        for (int rawSlot : e.getRawSlots()) {
            if (rawSlot < topSize) {
                e.setCancelled(true);
                deny(player);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player player)) return;
        if (!isTradeGui(e.getView())) return;

        // Resincroniza el inventario un tick después para eliminar ítems fantasma
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.updateInventory();
            sweepPlayer(player, new HashMap<>());
        }, 1L);
    }
}
