package dev.fce;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * RACHA DE SUERTE VISIBLE.
 *
 * En cuanto un jugador toma un Libro de Encantamiento con el cursor, el lore
 * del libro se reescribe para mostrarle SU racha de fallos en ese tier, el
 * bono de pity que ya tiene acumulado (% de exito real) y el bono que tendria
 * tras un fallo mas. El jugador ve la tension antes de aplicar:
 *
 *   │ ☘ Racha        · 3 fallos seguidos
 *   │ ☘ Bono         · +9%  (exito real 54%)
 *   │ Un fallo mas   → +12%
 *
 * Detalles de diseno:
 *  - Solo se reescribe LORE (estetica). Los Data Components del libro no se
 *    tocan jamas: el % de exito real del item permanece intacto y la tirada
 *    de DragAndDropListener sigue siendo la unica fuente de verdad.
 *  - El bono es del JUGADOR, no del libro: al refrescar en el momento de
 *    tomarlo con el cursor, un libro intercambiado muestra siempre la racha
 *    de quien lo sostiene, nunca la del dueno anterior. Si la racha es 0,
 *    la plantilla base borra cualquier bloque de suerte previo.
 *  - Corre en MONITOR con ignoreCancelled: los clicks que cancelan la capa
 *    antidupe o las GUIs del plugin nunca llegan aqui, y el refresco real se
 *    hace al tick siguiente, cuando el estado del cursor ya esta consolidado
 *    (mismo patron que el inspector, ver README seccion 12).
 *  - Los numeros provienen de PlayerStats (luckBonus / nextLuckBonus), asi
 *    que el lore siempre coincide con el bono que la tirada aplicara.
 */
public class LuckLoreListener implements Listener {

    private final FabledCustomEnchantsPlugin plugin;

    public LuckLoreListener(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!plugin.getConfig().getBoolean("luck.enabled", true)) return;

        // Pre-filtro barato: solo interesa si hay un libro encantado en juego
        // (tomandolo del slot o soltandolo desde el cursor).
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean bookInvolved =
                (current != null && current.getType() == Material.ENCHANTED_BOOK)
                        || (cursor != null && cursor.getType() == Material.ENCHANTED_BOOK);
        if (!bookInvolved) return;

        // El resultado real del click (que quedo en el cursor) se consolida
        // al tick siguiente; refrescar aqui mismo pintaria un estado a medias.
        Bukkit.getScheduler().runTask(plugin, () -> refreshCursor(player));
    }

    /** Reescribe el lore del libro que el jugador sostiene con el cursor. */
    private void refreshCursor(Player player) {
        if (!player.isOnline()) return;
        ItemStack cursor = player.getItemOnCursor();
        if (cursor.getType() != Material.ENCHANTED_BOOK || !cursor.hasItemMeta()) return;

        PersistentDataContainer pdc = cursor.getItemMeta().getPersistentDataContainer();
        String enchantId = pdc.get(Keys.ENCHANT_ID, PersistentDataType.STRING);
        if (enchantId == null) return;                                          // libro vanilla
        if (pdc.get(Keys.SUCCESS, PersistentDataType.INTEGER) == null) return;  // libro corrupto

        EnchantDefinition def = plugin.enchants().get(enchantId);
        if (def == null) return;
        TierRegistry.Tier tier = plugin.tiers().get(def.tierId());
        if (tier == null) return;

        String tierId = tier.id();
        int streak = plugin.stats().streak(player.getUniqueId(), tierId);
        int bonus = plugin.stats().luckBonus(player, tierId);
        int next = plugin.stats().nextLuckBonus(player, tierId);

        plugin.books().refreshWithLuck(cursor, def, tier, streak, bonus, next);
        player.setItemOnCursor(cursor);
    }
}
