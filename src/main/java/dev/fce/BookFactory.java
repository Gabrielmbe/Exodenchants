package dev.fce;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fabrica de Libros de Encantamiento a partir de la plantilla
 * books/libro_encantamiento.yml. Estetica en Lore (MiniMessage),
 * datos reales en Data Components (PDC).
 *
 * Placeholder nuevo: {aplicable} -> lista legible de los grupos de items
 * donde puede usarse el encantamiento (enchants/<id>.yml -> applicable-to).
 * Los nombres visibles se personalizan en libro_encantamiento.yml ->
 * group-names; si un grupo no esta ahi, se usa un nombre por defecto.
 *
 * RACHA DE SUERTE VISIBLE (refreshWithLuck): bloque opcional luck-lore de
 * la plantilla que muestra al portador su racha de fallos del tier, el bono
 * de pity actual y el que tendria tras un fallo mas. Placeholders extra:
 * {racha} {bono} {exito_efectivo} {siguiente_bono}.
 */
public class BookFactory {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String[] ROMAN = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private String displayName = "{enchant} {nivel_romano}";
    private List<String> loreTemplate = new ArrayList<>();
    private List<String> luckLoreTemplate = new ArrayList<>();
    private final Map<String, String> groupNames = new LinkedHashMap<>();

    public void load(JavaPlugin plugin) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "books/libro_encantamiento.yml"));
        displayName = yml.getString("display-name", displayName);
        loreTemplate = yml.getStringList("lore");
        luckLoreTemplate = yml.getStringList("luck-lore");

        groupNames.clear();
        ConfigurationSection names = yml.getConfigurationSection("group-names");
        if (names != null) {
            for (String key : names.getKeys(false)) {
                groupNames.put(key.toUpperCase(Locale.ROOT), names.getString(key, key));
            }
        }
    }

    public static String roman(int level) {
        return level >= 0 && level < ROMAN.length ? ROMAN[level] : String.valueOf(level);
    }

    /** Deserializa MiniMessage y quita la cursiva por defecto de los items. */
    public static Component line(String miniMessage) {
        return MM.deserialize(miniMessage).decoration(TextDecoration.ITALIC, false);
    }

    public ItemStack create(EnchantDefinition def, TierRegistry.Tier tier,
                            int level, int success, int destroy) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();

        write(meta, def, tier, level, success, destroy);

        var pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.ENCHANT_ID, PersistentDataType.STRING, def.id());
        pdc.set(Keys.ENCHANT_LEVEL, PersistentDataType.INTEGER, level);
        pdc.set(Keys.SUCCESS, PersistentDataType.INTEGER, success);
        pdc.set(Keys.DESTROY, PersistentDataType.INTEGER, destroy);
        pdc.set(Keys.TIER, PersistentDataType.STRING, tier.id());

        book.setItemMeta(meta);
        return book;
    }

    /**
     * Reescribe nombre y Lore de un libro existente a partir de sus propios
     * Data Components. Se usa tras aplicar un polvo magico, para que la
     * estetica refleje el nuevo % de ruptura sin tocar los datos.
     */
    public void refresh(ItemStack book, EnchantDefinition def, TierRegistry.Tier tier) {
        if (book == null || !book.hasItemMeta()) return;
        ItemMeta meta = book.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int level = pdc.getOrDefault(Keys.ENCHANT_LEVEL, PersistentDataType.INTEGER, 1);
        int success = pdc.getOrDefault(Keys.SUCCESS, PersistentDataType.INTEGER, 100);
        int destroy = pdc.getOrDefault(Keys.DESTROY, PersistentDataType.INTEGER, 0);
        write(meta, def, tier, level, success, destroy);
        book.setItemMeta(meta);
    }

    /**
     * RACHA DE SUERTE VISIBLE.
     * Igual que refresh(), pero ademas anexa el bloque luck-lore de la
     * plantilla cuando el portador acumula racha o bono en el tier del libro.
     *
     * Como write() reconstruye el lore completo desde la plantilla base,
     * cualquier bloque de racha anterior (de otro jugador o de una racha ya
     * reiniciada) desaparece solo: el lore nunca queda desactualizado en
     * negativo, y los datos reales del libro (PDC) no se tocan jamas.
     *
     * @param streak    fallos consecutivos del portador en este tier
     * @param bonus     bono de pity actual (ya con techo aplicado)
     * @param nextBonus bono que tendria tras UN fallo mas (la tension)
     */
    public void refreshWithLuck(ItemStack book, EnchantDefinition def, TierRegistry.Tier tier,
                                int streak, int bonus, int nextBonus) {
        if (book == null || !book.hasItemMeta()) return;
        ItemMeta meta = book.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int level = pdc.getOrDefault(Keys.ENCHANT_LEVEL, PersistentDataType.INTEGER, 1);
        int success = pdc.getOrDefault(Keys.SUCCESS, PersistentDataType.INTEGER, 100);
        int destroy = pdc.getOrDefault(Keys.DESTROY, PersistentDataType.INTEGER, 0);

        write(meta, def, tier, level, success, destroy);

        if ((streak > 0 || bonus > 0) && !luckLoreTemplate.isEmpty()) {
            int effective = Math.min(100, success + bonus);
            List<Component> base = meta.lore();
            List<Component> extended = base == null ? new ArrayList<>() : new ArrayList<>(base);
            for (String raw : luckLoreTemplate) {
                String resolved = apply(raw, def, tier, level, success, destroy)
                        .replace("{racha}", String.valueOf(streak))
                        .replace("{bono}", String.valueOf(bonus))
                        .replace("{exito_efectivo}", String.valueOf(effective))
                        .replace("{siguiente_bono}", String.valueOf(nextBonus));
                extended.add(line(resolved));
            }
            meta.lore(extended);
        }
        book.setItemMeta(meta);
    }

    /** Pinta nombre, Lore y flags segun la plantilla. */
    private void write(ItemMeta meta, EnchantDefinition def, TierRegistry.Tier tier,
                       int level, int success, int destroy) {
        meta.displayName(line(apply(displayName, def, tier, level, success, destroy)));
        List<Component> lore = new ArrayList<>();
        for (String raw : loreTemplate) {
            lore.add(line(apply(raw, def, tier, level, success, destroy)));
        }
        meta.lore(lore);

        // Data Component nativo de 1.20.5+ (enchantment_glint_override)
        meta.setEnchantmentGlintOverride(tier.glint());
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
    }

    private String apply(String raw, EnchantDefinition def, TierRegistry.Tier tier,
                         int level, int success, int destroy) {
        // Orden importante: los placeholders largos van antes que sus prefijos
        return raw
                .replace("{aplicable}", applicableLabel(def))
                .replace("{id}", def.id())
                .replace("{enchant}", def.displayName())
                .replace("{nivel_romano}", roman(level))
                .replace("{nivel}", String.valueOf(level))
                .replace("{exito}", String.valueOf(success))
                .replace("{ruptura}", String.valueOf(destroy))
                .replace("{tier_color}", tier.color())
                .replace("{tier_id}", tier.id())
                .replace("{tier_glint}", String.valueOf(tier.glint()))
                .replace("{tier}", tier.display());
    }

    // ------------------------------------------------------------
    // {aplicable}: grupos de items donde funciona el encantamiento
    // ------------------------------------------------------------

    /** "Picos, Hachas" a partir de enchants/<id>.yml -> applicable-to. */
    private String applicableLabel(EnchantDefinition def) {
        List<String> parts = new ArrayList<>();
        for (String group : def.applicableGroups()) {
            parts.add(groupDisplay(group));
        }
        if (parts.isEmpty()) return "Todos";
        return String.join("<dark_gray>, </dark_gray><white>", parts);
    }

    /** Nombre visible de un grupo: group-names del yml o default interno. */
    private String groupDisplay(String group) {
        String key = group.toUpperCase(Locale.ROOT);
        String custom = groupNames.get(key);
        if (custom != null && !custom.isBlank()) return custom;
        return switch (key) {
            case "PICKAXE", "PICKAXES" -> "Picos";
            case "AXE", "AXES" -> "Hachas";
            case "SHOVEL", "SHOVELS" -> "Palas";
            case "HOE", "HOES" -> "Azadas";
            case "SWORD", "SWORDS" -> "Espadas";
            case "BOW", "BOWS" -> "Arcos";
            case "CROSSBOW", "CROSSBOWS" -> "Ballestas";
            case "TRIDENT", "TRIDENTS" -> "Tridentes";
            case "HELMET", "HELMETS" -> "Cascos";
            case "CHESTPLATE", "CHESTPLATES" -> "Pecheras";
            case "LEGGINGS" -> "Pantalones";
            case "BOOTS" -> "Botas";
            case "ARMOR" -> "Armadura";
            case "TOOLS" -> "Herramientas";
            case "WEAPONS" -> "Armas";
            case "ELYTRA" -> "Élitros";
            case "SHIELD", "SHIELDS" -> "Escudos";
            default -> pretty(key);
        };
    }

    /** Fallback: "VEIN_TOOLS" -> "Vein tools". */
    private String pretty(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }
}
