package dev.fce.security;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
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
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Módulo antidupe de FabledCustomEnchants (v3.1).
 *
 * Qué hace:
 *  1) Bloquea meter/sacar ítems del plugin (libros fe_id y polvos/esencias
 *     fd_id) en GUIs de comercio: villagers (MERCHANT) AJENOS al plugin,
 *     GUIs cuyo InventoryHolder pertenece a un plugin de trade conocido
 *     (configurable en antidupe.trade-holder-packages) y — como red de
 *     apoyo — cualquier GUI cuyo título contenga palabras de trade
 *     (antidupe.trade-keywords). Ahí viven los exploits de desincronización
 *     de inventario.
 *  2) Bloquea meter ítems del plugin en estaciones que transforman ítems
 *     (yunque, esmeril, mesa de herrería, telar, mesa de encantar...), que
 *     pueden clonar o corromper los Data Components.
 *  3) Cubre explícitamente el swap de mano secundaria (tecla F,
 *     ClickType.SWAP_OFFHAND), que según la versión no pasa por la rama de
 *     getHotbarButton().
 *  4) Resincroniza el inventario del jugador un tick después de cerrar una
 *     GUI de comercio, una estación bloqueada o un menú del propio plugin,
 *     para matar ítems fantasma del lado del cliente.
 *
 * EXCEPCIÓN — Encantador Errante (v3.1):
 *  El NPC propio del plugin (TraderManager, marcado con la clave fe_trader
 *  en su PersistentDataContainer) VENDE libros y polvos del sistema a
 *  través de la GUI vanilla de merchant. Bloquear su GUI hacía imposible
 *  comprarle: el jugador recibía "No puedes usar libros..." al retirar el
 *  resultado. Su inventario de merchant se considera DE CONFIANZA: los
 *  trades los construye el propio plugin y la transacción la ejecuta el
 *  código vanilla, exactamente igual que con un bibliotecario normal.
 *
 * Prioridad: LOW en los eventos de clic/drag, para que esta capa de
 * seguridad evalúe SIEMPRE antes que la mecánica de drag &amp; drop
 * (DragAndDropListener corre en HIGH con ignoreCancelled=true).
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
 *     antiDupe = dev.fce.security.AntiDupeListener.register(this);
 * y tras /fce reload:
 *     antiDupe.reload();
 */
public final class AntiDupeListener implements Listener {

    /** Clave PDC que marca al Encantador Errante propio (ver TraderManager). */
    private static final String TRADER_KEY = "fe_trader";

    /** Títulos de GUI (en minúsculas) que se consideran de comercio por defecto. */
    private static final List<String> DEFAULT_TRADE_KEYWORDS = List.of(
            "trade", "trueque", "intercambio", "comercio"
    );

    /**
     * Paquetes (en minúsculas) de InventoryHolders de plugins de comercio
     * conocidos. Detección robusta e independiente del idioma del título.
     * Ampliable en config.yml → antidupe.trade-holder-packages.
     */
    private static final List<String> DEFAULT_TRADE_HOLDER_PACKAGES = List.of(
            "de.codingair.tradesystem",   // TradeSystem
            "com.trophonix.tradeplus",    // TradePlus
            "me.dniym.trade",             // iTrade / variantes
            "net.tnemc.tnt"               // TheNewTrade
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
    private final Set<String> tradeHolderPackages = new HashSet<>();

    public AntiDupeListener(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    /**
     * Relee keywords y paquetes de holders desde config.yml. Debe llamarse
     * tras /fce reload: antes las keywords solo se leían en el constructor y
     * los cambios de config no surtían efecto hasta reiniciar.
     */
    public void reload() {
        tradeKeywords.clear();
        List<String> configured = plugin.getConfig().getStringList("antidupe.trade-keywords");
        for (String kw : configured.isEmpty() ? DEFAULT_TRADE_KEYWORDS : configured) {
            tradeKeywords.add(kw.toLowerCase(Locale.ROOT));
        }
        tradeHolderPackages.clear();
        List<String> holders = plugin.getConfig().getStringList("antidupe.trade-holder-packages");
        for (String pkg : holders.isEmpty() ? DEFAULT_TRADE_HOLDER_PACKAGES : holders) {
            tradeHolderPackages.add(pkg.toLowerCase(Locale.ROOT));
        }
    }

    /** Registra el listener y devuelve la instancia (para poder recargarla). */
    public static AntiDupeListener register(Plugin plugin) {
        AntiDupeListener listener = new AntiDupeListener(plugin);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        return listener;
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
        if (view.getTopInventory().getType() == InventoryType.MERCHANT) {
            // EXCEPCIÓN: el Encantador Errante del propio plugin vende
            // libros/polvos del sistema; su GUI es de confianza. Sin esta
            // exención era imposible comprarle (el retiro del resultado
            // disparaba el bloqueo "No puedes usar libros...").
            return !isOwnTrader(view.getTopInventory());
        }

        // Detección robusta: el holder de la GUI pertenece a un plugin de
        // comercio conocido (independiente del idioma/estilo del título).
        InventoryHolder holder = view.getTopInventory().getHolder();
        if (holder != null) {
            String cls = holder.getClass().getName().toLowerCase(Locale.ROOT);
            for (String pkg : tradeHolderPackages) {
                if (cls.startsWith(pkg)) return true;
            }
        }

        // Red de apoyo: keywords en el título (frágil pero mejor que nada).
        String title = ChatColor.stripColor(view.getTitle()).toLowerCase(Locale.ROOT);
        for (String kw : tradeKeywords) {
            if (title.contains(kw)) return true;
        }
        return false;
    }

    /**
     * ¿La GUI de merchant pertenece al Encantador Errante de este plugin?
     * Se identifica por la clave fe_trader en el PersistentDataContainer
     * de la entidad comerciante (la pone TraderManager al invocarlo).
     */
    private boolean isOwnTrader(Inventory top) {
        if (!(top instanceof MerchantInventory merchantInv)) return false;
        if (!(merchantInv.getMerchant() instanceof Entity entity)) return false;
        for (NamespacedKey key : entity.getPersistentDataContainer().getKeys()) {
            if (key.getKey().equals(TRADER_KEY)) return true;
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

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
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

        // Swap de mano secundaria (tecla F): según la versión no pasa por
        // getHotbarButton(), así que se comprueba la offhand explícitamente.
        ItemStack offhandItem = e.getClick() == ClickType.SWAP_OFFHAND
                ? player.getInventory().getItemInOffHand()
                : null;

        boolean blocked =
                // Colocar desde el cursor, swap con hotbar o swap con offhand
                // dentro de la GUI
                (clickedTop && (isFceItem(e.getCursor())
                        || isFceItem(hotbarItem)
                        || isFceItem(offhandItem)))
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

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
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
