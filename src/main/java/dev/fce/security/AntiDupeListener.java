package dev.fce.security;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Módulo antidupe de FabledCustomEnchants (v2).
 *
 * Qué hace:
 *  1) Bloquea meter/sacar ítems del plugin (libros fe_id y polvos/esencias
 *     fd_id) en GUIs de comercio: villagers (MERCHANT) y cualquier GUI cuyo
 *     título contenga palabras de trade (configurable en config.yml →
 *     antidupe.trade-keywords). Ahí viven los exploits de desincronización
 *     de inventario.
 *  2) Bloquea meter ítems del plugin en estaciones que transforman ítems
 *     (yunque, esmeril, mesa de herrería, telar, mesa de encantar...), que
 *     pueden clonar o corromper los Data Components.
 *  3) Resincroniza el inventario del jugador un tick después de cerrar una
 *     GUI de comercio, una estación bloqueada o un menú del propio plugin,
 *     para matar ítems fantasma del lado del cliente.
 *
 * Qué NO hace (a propósito):
 *  - Ya no sella libros con UID ni borra "clones". Ese sistema eliminaba
 *    ítems legítimos: dos libros idénticos se apilaban antes de sellarse,
 *    el stack completo recibía UN solo UID y al separarlos el barrido
 *    destruía uno. También sellaba los ítems de exhibición de las GUIs
 *    (catálogo, mercado), provocando borrados al comprar. Los jugadores
 *    pueden tener tantos libros iguales como quieran.
 *
 * Registro (en FabledCustomEnchantsPlugin.onEnable()):
 *     dev.fce.security.AntiDupeListener.register(this);
 */
public final class AntiDupeListener implements Listener {

    /** Títulos de GUI (en minúsculas) que se consideran de comercio por defecto. */
    private static final List<String> DEFAULT_TRADE_KEYWORDS = List.of(
            "trade", "trueque", "intercambio", "comercio"
    );

    /** Estaciones que transforman ítems: se bloquea INSERTAR ítems del plugin. */
    private static final Set<InventoryType> BLOCKED_STATIONS = Set.of(
            InventoryType.ANVIL,
            InventoryType.GRINDSTONE,
            InventoryType.SMITHING,
            InventoryType.CARTOGRAPHY,
            InventoryType.LOOM,
            InventoryType.STONECUTTER,
            InventoryType.ENCHANTING
    );

    private final Plugin plugin;
    private final Set<String> tradeKeywords = new HashSet<>();

    public AntiDupeListener(Plugin plugin) {
        this.plugin = plugin;
        List<String> configured = plugin.getConfig().getStringList("antidupe.trade-keywords");
        for (String kw : configured.isEmpty() ? DEFAULT_TRADE_KEYWORDS : configured) {
            tradeKeywords.add(kw.toLowerCase(Locale.ROOT));
        }
    }

    /** Registra el listener. Ya no hay barrido periódico: no borra ítems. */
    public static void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new AntiDupeListener(plugin), plugin);
    }

    /* ==================== Identificación de ítems ==================== */

    /** ¿Es un ítem del plugin? (libro fe_id o polvo/esencia fd_id, en cualquier namespace) */
    private boolean isFceItem(ItemStack item) {
        return hasDataKey(item, "fe_id") || hasDataKey(item, "fd_id");
    }

    private boolean hasDataKey(ItemStack item, String key) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        for (NamespacedKey k : pdc.getKeys()) {
            if (k.getKey().equals(key)) return true;
        }
        return false;
    }

    /* ==================== Clasificación de GUIs ==================== */

    private boolean isTradeGui(InventoryView view) {
        if (view.getTopInventory().getType() == InventoryType.MERCHANT) return true;
        String title = ChatColor.stripColor(view.getTitle()).toLowerCase(Locale.ROOT);
        for (String kw : tradeKeywords) {
            if (title.contains(kw)) return true;
        }
        return false;
    }

    private boolean isBlockedStation(InventoryView view) {
        return BLOCKED_STATIONS.contains(view.getTopInventory().getType());
    }

    /** ¿Es un menú del propio plugin? (MenuHolder u otro holder de dev.fce) */
    private boolean isPluginGui(InventoryView view) {
        InventoryHolder holder = view.getTopInventory().getHolder();
        return holder != null && holder.getClass().getName().startsWith("dev.fce.");
    }

    private void deny(Player player) {
        player.sendMessage(ChatColor.RED + "No puedes usar libros, polvos ni esencias en este menú.");
        player.updateInventory();
    }

    /* ==================== Eventos ==================== */

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;

        boolean trade = isTradeGui(e.getView());
        boolean station = isBlockedStation(e.getView());
        if (!trade && !station) return;

        Inventory top = e.getView().getTopInventory();
        boolean clickedTop = e.getClickedInventory() != null
                && e.getClickedInventory().equals(top);

        ItemStack hotbarItem = e.getHotbarButton() >= 0
                ? player.getInventory().getItem(e.getHotbarButton())
                : null;

        boolean blocked =
                // Colocar desde el cursor o swap con hotbar dentro de la GUI
                (clickedTop && (isFceItem(e.getCursor()) || isFceItem(hotbarItem)))
                // Shift-click desde el inventario propio hacia la GUI
                || (!clickedTop
                        && e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                        && isFceItem(e.getCurrentItem()))
                // Doble clic (COLLECT_TO_CURSOR) recolectando ítems FCE a través de la GUI
                || (e.getClick() == ClickType.DOUBLE_CLICK && isFceItem(e.getCursor()));

        // Solo en GUIs de comercio: tampoco se puede SACAR un ítem FCE que ya
        // esté dentro (slots de resultado de dupes). En estaciones no se aplica
        // para no dejar ítems atrapados.
        if (trade) {
            blocked = blocked || (clickedTop && isFceItem(e.getCurrentItem()));
        }

        if (blocked) {
            e.setCancelled(true);
            deny(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!isTradeGui(e.getView()) && !isBlockedStation(e.getView())) return;
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
        if (!isTradeGui(e.getView()) && !isBlockedStation(e.getView()) && !isPluginGui(e.getView())) return;

        // Resincroniza un tick después para eliminar ítems fantasma del cliente
        Bukkit.getScheduler().runTaskLater(plugin, player::updateInventory, 1L);
    }
}
