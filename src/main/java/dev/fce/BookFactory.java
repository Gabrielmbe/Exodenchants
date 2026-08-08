package dev.fce;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Fabrica de Libros de Encantamiento a partir de la plantilla
 * books/libro_encantamiento.yml. Estetica en Lore (MiniMessage),
 * datos reales en Data Components (PDC).
 */
public class BookFactory {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String[] ROMAN = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    private String displayName = "{enchant} {nivel_romano}";
    private List<String> loreTemplate = new ArrayList<>();

    public void load(JavaPlugin plugin) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "books/libro_encantamiento.yml"));
        displayName = yml.getString("display-name", displayName);
        loreTemplate = yml.getStringList("lore");
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
}
