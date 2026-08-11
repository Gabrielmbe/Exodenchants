package dev.fce;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mecanica Drag &amp; Drop con dos ramas segun lo que lleve el cursor:
 *
 *  1) LIBRO sobre equipo  -> tirada 1-100 contra el % de exito del libro.
 *     Si roll &lt;= exito aplica el encantamiento; si no, consume el libro y
 *     evalua la tirada de ruptura del item.
 *
 *  2) POLVO MAGICO sobre libro -> modifica las probabilidades del libro de
 *     forma garantizada (sin tirada) y consume una unidad del polvo:
 *       mode: destroy -> baja el % de ruptura
 *       mode: success -> sube el % de exito
 *
 * En ambos casos los datos se leen y escriben SOLO en Data Components.
 *
 * DRAMA (ForgeSuspense + casi-fallos):
 *  - Si suspense.on-apply esta activo y el exito efectivo es < 100, el
 *    veredicto se revela tras ~1.5s de redoble de yunque y particulas.
 *    El libro entra en la forja de inmediato (se consume del cursor);
 *    el inventario queda bloqueado hasta la revelacion.
 *  - Fallo por 1-3 puntos -> "La forja fallo... ¡por un X%!".
 *  - Exito con margen <= 2 -> "¡Exito por los pelos!" con sonido propio.
 *  - Ruptura esquivada por 1-3 puntos -> mensaje de supervivencia y, en
 *    tiers altos, anuncio global (announce.on-survived).
 *
 * SEGURIDAD:
 *  - Solo se opera sobre ITEMS DEL INVENTARIO DEL PROPIO JUGADOR. Los clics
 *    sobre la GUI superior (catalogo, mercado negro, menus de otros plugins,
 *    cofres ajenos...) se ignoran por completo: sin este filtro un libro
 *    podia encantar un item de exhibicion o incluso borrarlo si saltaba la
 *    tirada de ruptura, consumiendo ademas el libro.
 *  - Corre en prioridad HIGH con ignoreCancelled=true: la capa antidupe
 *    (dev.fce.security.AntiDupeListener, prioridad LOW) evalua SIEMPRE antes
 *    y puede cancelar el clic.
 *  - Un libro sin % de exito almacenado se considera corrupto (NBT editado o
 *    resto de una version vieja) y se rechaza sin consumirlo. Nunca se asume
 *    100% de exito por defecto.
 *
 * COSMETICA INTEGRADA (rebuildCosmetics):
 *  - Sin cabecera "=== ENCANTAMIENTOS ===": las lineas van integradas al
 *    lore con barra lateral, ordenadas de MAYOR a MENOR tier.
 *  - Linea resumen al final: "│ Encantos · X/Y  ✦ Tier".
 *  - El item se renombra con el color del tier dominante:
 *        » Pico de Netherita «
 *    usando el nombre traducido del cliente (<lang:...>), asi funciona
 *    para cualquier material e idioma. Solo se renombra si el item no
 *    tiene nombre custom previo o si el nombre lo puso este plugin
 *    (marca fce_named), para no pisar nombres de yunque.
 *
 * Config opcional (todo tiene defaults sensatos):
 *   cosmetics.item-name-format: "<dark_gray>» <color:{tier_color}>{item}</color> <dark_gray>«"
 *   cosmetics.tier-colors.<tierId>: "#F72585"
 *   cosmetics.tier-symbols.<tierId>: "<gray>◆"   (ya existente)
 */
public class DragAndDropListener implements Listener {

    private final FabledCustomEnchantsPlugin plugin;
    private final NamespacedKey namedKey;

    public DragAndDropListener(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
        this.namedKey = Keys.of("fce_named");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;

        // SEGURIDAD: solo items del inventario del PROPIO jugador. Un clic
        // sobre la GUI superior (menus del plugin, GUIs de otros plugins,
        // cofres...) se ignora: jamas se encanta ni se rompe un item que no
        // este en el inventario personal.
        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getBottomInventory()) {
            return;
        }

        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir() || !cursor.hasItemMeta()) return;
        PersistentDataContainer cursorData = cursor.getItemMeta().getPersistentDataContainer();

        // --- Rama 2: polvo magico ---
        String dustId = cursorData.get(Keys.DUST_ID, PersistentDataType.STRING);
        if (dustId != null) {
            handleDust(event, player, cursor, dustId);
            return;
        }

        // --- Rama 1: libro de encantamiento ---
        if (cursor.getType() != Material.ENCHANTED_BOOK) return;
        String enchantId = cursorData.get(Keys.ENCHANT_ID, PersistentDataType.STRING);
        if (enchantId == null) return; // libro vanilla: se ignora por completo

        ItemStack target = event.getCurrentItem();
        if (target == null || target.getType().isAir()) return;

        event.setCancelled(true);

        EnchantDefinition def = plugin.enchants().get(enchantId);
        if (def == null) return;

        int level = cursorData.getOrDefault(Keys.ENCHANT_LEVEL, PersistentDataType.INTEGER, 1);

        // Un libro sin % de exito es un item manipulado (NBT editors) o de una
        // version vieja: se rechaza como corrupto SIN consumirlo. Asumir 100
        // por defecto convertia cualquier libro falsificado en aplicacion
        // garantizada.
        Integer successStored = cursorData.get(Keys.SUCCESS, PersistentDataType.INTEGER);
        if (successStored == null) {
            plugin.messages().playSound(player, "purchase-denied");
            plugin.messages().sendRaw(player,
                    "<red>Este libro está corrupto y no puede aplicarse.");
            return;
        }
        int success = Math.max(0, Math.min(100, successStored));
        int destroy = cursorData.getOrDefault(Keys.DESTROY, PersistentDataType.INTEGER, 0);

        // 1) Compatibilidad del item destino (grupos de drag_and_drop.yml)
        if (!plugin.enchants().isApplicable(def, target.getType())) {
            plugin.messages().send(player, "not-applicable");
            return;
        }

        ItemMeta targetMeta = target.getItemMeta();
        PersistentDataContainer itemData = targetMeta.getPersistentDataContainer();

        // 2) Nivel existente y limite global de encantamientos por item
        int existing = itemData.getOrDefault(Keys.enchantOnItem(def.id()), PersistentDataType.INTEGER, 0);
        if (existing >= level) {
            plugin.messages().send(player, "already-has", "enchant", def.displayName());
            return;
        }
        int maxEnchants = plugin.getConfig().getInt("limits.max-enchants-per-item", 4);
        if (existing == 0 && countEnchants(itemData) >= maxEnchants) {
            plugin.messages().send(player, "max-enchants", "max", String.valueOf(maxEnchants));
            return;
        }

        // 3) Tirada 1-100 contra el % de exito, con el bono de la RACHA DE SUERTE:
        //    cada fallo consecutivo del mismo tier suma puntos al intento siguiente.
        TierRegistry.Tier tier = plugin.tiers().get(def.tierId());
        String tierId = tier == null ? def.tierId() : tier.id();
        int luck = plugin.stats().luckBonus(player, tierId);
        int effective = Math.min(100, success + luck);

        int roll = ThreadLocalRandom.current().nextInt(1, 101);
        // El libro entra en la forja YA: se consume del cursor antes del
        // redoble. El resultado se revela al final del suspenso.
        event.getView().setCursor(null);

        if (luck > 0) {
            plugin.messages().send(player, "luck-applied",
                    "bono", String.valueOf(luck),
                    "exito", String.valueOf(effective));
        }

        // Todo lo que toca el inventario tras el suspenso usa inv+slot, nunca
        // el evento (ya habra terminado). ForgeSuspense bloquea el inventario
        // mientras tanto, asi que el slot no puede cambiar.
        final Inventory inv = event.getClickedInventory();
        final int slot = event.getSlot();
        final int fEffective = effective;
        final int fRoll = roll;
        final TierRegistry.Tier fTier = tier;
        final String fTierId = tierId;

        Runnable resolution = () -> resolveApply(player, inv, slot, target, targetMeta,
                itemData, def, level, success, destroy, fTier, fTierId, fRoll, fEffective);

        ForgeSuspense suspense = ForgeSuspense.get(plugin);
        boolean dramatic = suspense.enabled()
                && plugin.getConfig().getBoolean("suspense.on-apply", true)
                && effective < 100; // con exito garantizado no hay tension posible
        if (dramatic) {
            suspense.begin(player, resolution);
        } else {
            resolution.run();
        }
    }

    /** Veredicto de la tirada: exito (con posible "por los pelos") o fallo. */
    private void resolveApply(Player player, Inventory inv, int slot, ItemStack target,
                              ItemMeta targetMeta, PersistentDataContainer itemData,
                              EnchantDefinition def, int level, int success, int destroy,
                              TierRegistry.Tier tier, String tierId, int roll, int effective) {
        if (roll <= effective) {
            applyEnchant(player, target, targetMeta, itemData, def, level);

            // CASI-PERDER, GANANDO: margen 0-2 -> "por los pelos", sonido propio.
            int margin = effective - roll;
            if (margin <= 2 && effective < 100) {
                player.playSound(player.getLocation(), "block.bell.use", 1.0f, 1.5f);
                plugin.messages().sendRaw(player,
                        "<gold>⚔ ¡Éxito por los pelos!</gold> <gray>La tirada entró por <white>"
                                + (margin + 1) + "</white> punto(s).");
            }

            plugin.stats().onSuccess(player, tierId);
            if (tier != null) plugin.announcer().applied(player, def, tier, level, success);
        } else {
            handleFailure(player, inv, slot, target, def, destroy, tier, roll - effective);
            int streak = plugin.stats().onFailure(player, tierId);
            int next = plugin.stats().luckBonus(player, tierId);
            if (next > 0) {
                plugin.messages().send(player, "luck-streak",
                        "racha", String.valueOf(streak),
                        "bono", String.valueOf(next));
            }
        }
    }

    // ------------------------------------------------------------
    // POLVOS MAGICOS
    // ------------------------------------------------------------
    private void handleDust(InventoryClickEvent event, Player player, ItemStack cursor, String dustId) {
        DustRegistry.Dust dust = plugin.dusts().get(dustId);
        if (dust == null) return;

        ItemStack target = event.getCurrentItem();
        if (target == null || target.getType().isAir()) return;

        event.setCancelled(true);

        // El polvo solo funciona sobre libros del sistema
        if (target.getType() != Material.ENCHANTED_BOOK || !target.hasItemMeta()) {
            plugin.messages().send(player, "dust-invalid-target");
            return;
        }
        ItemMeta bookMeta = target.getItemMeta();
        PersistentDataContainer bookData = bookMeta.getPersistentDataContainer();
        String enchantId = bookData.get(Keys.ENCHANT_ID, PersistentDataType.STRING);
        if (enchantId == null) {
            plugin.messages().send(player, "dust-invalid-target");
            return;
        }

        EnchantDefinition def = plugin.enchants().get(enchantId);
        TierRegistry.Tier tier = def == null ? null : plugin.tiers().get(def.tierId());
        if (def == null || tier == null) return;

        boolean applied = dust.boostsSuccess()
                ? boostSuccess(player, bookData, dust)
                : reduceDestroy(player, bookData, dust);
        if (!applied) return;

        target.setItemMeta(bookMeta);
        plugin.books().refresh(target, def, tier); // reescribe el Lore estetico
        consumeOne(event, cursor);

        plugin.messages().playSound(player, "dust-applied");
        player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                player.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.1);
    }

    /** mode: destroy -> resta puntos al % de ruptura, con suelo configurable. */
    private boolean reduceDestroy(Player player, PersistentDataContainer bookData, DustRegistry.Dust dust) {
        int min = plugin.getConfig().getInt("limits.min-destroy-rate", 0);
        int destroy = bookData.getOrDefault(Keys.DESTROY, PersistentDataType.INTEGER, 0);
        if (destroy <= min) {
            plugin.messages().playSound(player, "purchase-denied");
            plugin.messages().send(player, "dust-min-reached", "minimo", String.valueOf(min));
            return false;
        }
        int updated = Math.max(min, destroy - dust.value());
        bookData.set(Keys.DESTROY, PersistentDataType.INTEGER, updated);

        plugin.messages().send(player, "dust-applied",
                "polvo", dust.displayName(),
                "anterior", String.valueOf(destroy),
                "ruptura", String.valueOf(updated));
        return true;
    }

    /** mode: success -> suma puntos al % de exito, con techo configurable. */
    private boolean boostSuccess(Player player, PersistentDataContainer bookData, DustRegistry.Dust dust) {
        int max = Math.min(100, plugin.getConfig().getInt("limits.max-success-rate", 100));
        // Default conservador: un libro sin % de exito almacenado se trata
        // como 0 (el polvo puede "reparar" libros de versiones viejas).
        int success = bookData.getOrDefault(Keys.SUCCESS, PersistentDataType.INTEGER, 0);
        if (success >= max) {
            plugin.messages().playSound(player, "purchase-denied");
            plugin.messages().send(player, "dust-max-reached", "maximo", String.valueOf(max));
            return false;
        }
        int updated = Math.min(max, success + dust.value());
        bookData.set(Keys.SUCCESS, PersistentDataType.INTEGER, updated);

        plugin.messages().send(player, "dust-boosted",
                "polvo", dust.displayName(),
                "anterior", String.valueOf(success),
                "exito", String.valueOf(updated));
        return true;
    }

    /** Gasta una unidad del stack que lleva el cursor. */
    private void consumeOne(InventoryClickEvent event, ItemStack cursor) {
        int amount = cursor.getAmount();
        if (amount <= 1) {
            event.getView().setCursor(null);
        } else {
            cursor.setAmount(amount - 1);
            event.getView().setCursor(cursor);
        }
    }

    // ------------------------------------------------------------
    // APLICACION DE ENCANTAMIENTOS
    // ------------------------------------------------------------
    private void applyEnchant(Player player, ItemStack target, ItemMeta meta,
                              PersistentDataContainer itemData, EnchantDefinition def, int level) {
        // Dato real -> Data Component; el Lore de abajo es pura estetica
        itemData.set(Keys.enchantOnItem(def.id()), PersistentDataType.INTEGER, level);

        // Nombre + lore integrados (sin cabecera), ordenados por tier
        rebuildCosmetics(target, meta, itemData);

        // BRILLO ENCANTADO: Data Component nativo de 1.20.5+. Hace que el pico,
        // la espada o la armadura se vean claramente encantados aunque no lleven
        // ningun encantamiento vanilla. Se ocultan los tooltips que ensucian.
        if (plugin.getConfig().getBoolean("cosmetics.glint", true)) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS,
                org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);

        target.setItemMeta(meta);

        plugin.messages().playSound(player, "apply-success"); // block.anvil.use
        player.getWorld().spawnParticle(Particle.ENCHANTED_HIT,
                player.getLocation().add(0, 1, 0), 40, 0.4, 0.6, 0.4, 0.15);
        plugin.messages().send(player, "apply-success",
                "enchant", def.displayName(), "nivel_romano", BookFactory.roman(level));

        // Si el item esta equipado, re-sincroniza la skill de Fabled ya mismo
        plugin.bridge().sync(player);
    }

    /**
     * Reconstruye nombre y lore del item a partir de sus Data Components.
     *  - Lineas de encanto con barra lateral, de MAYOR a MENOR tier.
     *  - Resumen final "│ Encantos · X/Y  ✦ Tier".
     *  - Renombra el item con el color del tier dominante.
     */
    private void rebuildCosmetics(ItemStack target, ItemMeta meta, PersistentDataContainer itemData) {
        // Tiers en orden inverso (el registro carga Comun -> Divino)
        List<TierRegistry.Tier> tiersDesc = new ArrayList<>(plugin.tiers().all());
        Collections.reverse(tiersDesc);

        Map<EnchantDefinition, Integer> present = new LinkedHashMap<>();
        TierRegistry.Tier topTier = null;
        for (TierRegistry.Tier t : tiersDesc) {
            for (EnchantDefinition other : plugin.enchants().all()) {
                if (!other.tierId().equalsIgnoreCase(t.id())) continue;
                int lvl = itemData.getOrDefault(
                        Keys.enchantOnItem(other.id()), PersistentDataType.INTEGER, 0);
                if (lvl > 0) {
                    present.put(other, lvl);
                    if (topTier == null) topTier = t;
                }
            }
        }
        if (present.isEmpty() || topTier == null) return;

        // --- Limpieza de lineas anteriores (cabecera vieja incluida) ---
        String header = plugin.getConfig().getString("cosmetics.lore-header", "");
        String footer = plugin.getConfig().getString("cosmetics.lore-footer", "");
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.removeIf(line -> {
            String plain = PlainTextComponentSerializer.plainText().serialize(line).trim();
            if (plain.isEmpty()) return true;
            if (!header.isBlank() && plain.equals(plainOf(header))) return true;
            if (!footer.isBlank() && plain.equals(plainOf(footer))) return true;
            if (plain.startsWith("Encantos") || plain.startsWith("│ Encantos")) return true;
            for (EnchantDefinition other : present.keySet()) {
                if (plain.contains(other.displayName())) return true;
            }
            return false;
        });

        // --- Lineas de encanto integradas ---
        lore.add(Component.empty());
        for (Map.Entry<EnchantDefinition, Integer> entry : present.entrySet()) {
            String symbol = plugin.getConfig().getString(
                    "cosmetics.tier-symbols." + entry.getKey().tierId(), "<gray>◆");
            lore.add(BookFactory.line("<dark_gray>│ " + symbol + " " + entry.getKey().loreLine()
                    .replace("{nivel_romano}", BookFactory.roman(entry.getValue()))
                    .replace("{nivel}", String.valueOf(entry.getValue()))));
        }

        // --- Resumen: cantidad y tier dominante ---
        int max = plugin.getConfig().getInt("limits.max-enchants-per-item", 4);
        String color = tierColor(topTier.id());
        String tierName = capitalize(topTier.id());
        lore.add(Component.empty());
        lore.add(BookFactory.line("<dark_gray>│ <gray>Encantos <dark_gray>· <white>"
                + present.size() + "/" + max + "</white>  <color:" + color + ">✦ "
                + tierName + "</color>"));
        meta.lore(lore);

        // --- Nombre con el color del tier dominante ---
        // Solo si el item no tiene nombre custom ajeno (respeta nombres de yunque)
        boolean namedByUs = itemData.has(namedKey, PersistentDataType.BYTE);
        if (!meta.hasDisplayName() || namedByUs) {
            String format = plugin.getConfig().getString("cosmetics.item-name-format",
                    "<dark_gray>» <color:{tier_color}>{item}</color> <dark_gray>«");
            String base = "<lang:" + target.getType().translationKey() + ">";
            Component name = BookFactory.line(format
                    .replace("{tier_color}", color)
                    .replace("{item}", base))
                    .decoration(TextDecoration.ITALIC, false);
            meta.displayName(name);
            itemData.set(namedKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    /** Color hex del tier: config cosmetics.tier-colors.<id> o default interno. */
    private String tierColor(String tierId) {
        String fallback = switch (tierId.toLowerCase(Locale.ROOT)) {
            case "comun" -> "#BDBDBD";
            case "raro" -> "#48CAE4";
            case "legendario" -> "#FFB703";
            case "mitico" -> "#F72585";
            case "divino" -> "#00F5D4";
            default -> "#AAAAAA";
        };
        return plugin.getConfig().getString("cosmetics.tier-colors." + tierId, fallback);
    }

    private String capitalize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.substring(0, 1).toUpperCase(Locale.ROOT)
                + raw.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * Fallo de la tirada de exito. El casi-fallo (1-3 puntos) se dramatiza y
     * la tirada de ruptura tiene su propio casi-perder: si el item se salva
     * por 1-3 puntos, se dice — y en tiers altos se anuncia al servidor.
     * Opera sobre inv+slot porque puede ejecutarse DESPUES del evento (tras
     * el suspenso); ForgeSuspense garantiza que el slot no cambio.
     */
    private void handleFailure(Player player, Inventory inv, int slot, ItemStack target,
                               EnchantDefinition def, int destroy, TierRegistry.Tier tier,
                               int failMargin) {
        plugin.messages().playSound(player, "apply-fail");
        player.getWorld().spawnParticle(Particle.ITEM,
                player.getLocation().add(0, 1, 0), 25, 0.3, 0.5, 0.3, 0.1,
                new ItemStack(Material.BOOK));
        plugin.messages().send(player, "apply-fail", "enchant", def.displayName());

        // CASI-GANAR, PERDIENDO: el mecanismo mas potente de las tragamonedas.
        if (failMargin >= 1 && failMargin <= 3) {
            plugin.messages().sendRaw(player,
                    "<red>La forja falló... <white>¡por un " + failMargin + "%!</white>");
        }

        // Segunda tirada: ruptura del item destino
        int destroyRoll = ThreadLocalRandom.current().nextInt(1, 101);
        if (destroyRoll <= destroy) {
            if (inv != null) inv.setItem(slot, null);
            plugin.messages().playSound(player, "item-destroyed");
            player.getWorld().spawnParticle(Particle.DUST,
                    player.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4,
                    new Particle.DustOptions(Color.fromRGB(0x7B2CBF), 1.2f));
            plugin.messages().send(player, "item-destroyed");
            if (tier != null) plugin.announcer().destroyed(player, def, tier);
        } else if (destroy > 0 && destroyRoll - destroy <= 3) {
            // SUPERVIVENCIA POR UN PELO: el item tembló... y sigue entero.
            player.playSound(player.getLocation(), "block.anvil.land", 0.8f, 0.6f);
            plugin.messages().sendRaw(player,
                    "<yellow>⚡ Tu objeto tembló en la forja... y sobrevivió por un <white>"
                            + (destroyRoll - destroy) + "%</white>.");
            if (tier != null) plugin.announcer().survived(player, def, tier);
        }
    }

    private String plainOf(String miniMessage) {
        return PlainTextComponentSerializer.plainText()
                .serialize(BookFactory.line(miniMessage)).trim();
    }

    private int countEnchants(PersistentDataContainer itemData) {
        int count = 0;
        for (EnchantDefinition def : plugin.enchants().all()) {
            if (itemData.has(Keys.enchantOnItem(def.id()), PersistentDataType.INTEGER)) count++;
        }
        return count;
    }
}
