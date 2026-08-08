package dev.fce;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Polvos magicos: consumibles de precio elevado que modifican las
 * probabilidades de un Libro de Encantamiento. Cada polvo es su propio
 * dusts/&lt;id&gt;.yml y declara su modo:
 *
 *   mode: destroy  -> RESTA puntos al % de ruptura del libro
 *   mode: success  -> SUMA puntos al % de exito del libro
 *
 * El valor real viaja en los Data Components (fd_id, fd_mode, fd_value); el
 * Lore es solo estetica y nunca se parsea.
 */
public class DustRegistry {

    public static final String MODE_DESTROY = "destroy";
    public static final String MODE_SUCCESS = "success";

    /** Definicion inmutable de un polvo, cargada desde dusts/&lt;id&gt;.yml. */
    public record Dust(String id, String displayName, Material material, boolean glint,
                       String mode, int value, double price, String color,
                       String nameTemplate, List<String> loreTemplate) {

        public boolean boostsSuccess() {
            return MODE_SUCCESS.equalsIgnoreCase(mode);
        }
    }

    private final Map<String, Dust> byId = new LinkedHashMap<>();

    public void load(JavaPlugin plugin) {
        byId.clear();
        File dir = new File(plugin.getDataFolder(), "dusts");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            if (!yml.getBoolean("enabled", true)) continue;
            String id = yml.getString("id");
            if (id == null || id.isBlank()) continue;
            id = id.toLowerCase(Locale.ROOT);

            Material material = Material.matchMaterial(yml.getString("material", "SUGAR"));
            if (material == null) material = Material.SUGAR;

            String mode = yml.getString("mode", MODE_DESTROY).toLowerCase(Locale.ROOT);
            if (!MODE_SUCCESS.equals(mode)) mode = MODE_DESTROY;

            // 'value' es la clave canonica; 'reduce' y 'boost' se aceptan como alias
            int value = yml.getInt("value", yml.getInt("reduce", yml.getInt("boost", 0)));

            byId.put(id, new Dust(
                    id,
                    yml.getString("display-name", id),
                    material,
                    yml.getBoolean("glint", false),
                    mode,
                    Math.max(0, value),
                    yml.getDouble("price", 0),
                    yml.getString("color", "#FFFFFF"),
                    yml.getString("item-name", "{polvo}"),
                    yml.getStringList("lore")));
        }
        plugin.getLogger().info("Polvos magicos cargados: " + byId.size());
    }

    public Dust get(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<Dust> all() {
        return byId.values();
    }

    /** Construye el item del polvo con sus Data Components. */
    public ItemStack create(Dust dust, int amount) {
        ItemStack stack = new ItemStack(dust.material(), Math.max(1, amount));
        ItemMeta meta = stack.getItemMeta();

        meta.displayName(BookFactory.line(apply(dust.nameTemplate(), dust)));
        List<Component> lore = new ArrayList<>();
        for (String raw : dust.loreTemplate()) {
            lore.add(BookFactory.line(apply(raw, dust)));
        }
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(dust.glint());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);

        var pdc = meta.getPersistentDataContainer();
        pdc.set(Keys.DUST_ID, PersistentDataType.STRING, dust.id());
        pdc.set(Keys.DUST_MODE, PersistentDataType.STRING, dust.mode());
        pdc.set(Keys.DUST_VALUE, PersistentDataType.INTEGER, dust.value());

        stack.setItemMeta(meta);
        return stack;
    }

    private String apply(String raw, Dust dust) {
        return raw
                .replace("{polvo}", dust.displayName())
                .replace("{valor}", String.valueOf(dust.value()))
                .replace("{reduccion}", String.valueOf(dust.value()))
                .replace("{aumento}", String.valueOf(dust.value()))
                .replace("{precio}", String.format(Locale.US, "%,.0f", dust.price()))
                .replace("{color}", dust.color());
    }
}
