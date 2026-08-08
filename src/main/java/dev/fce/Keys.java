package dev.fce;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/**
 * Claves del PersistentDataContainer (minecraft:custom_data).
 * Unica fuente de verdad de los datos de libros, polvos e items:
 * el Lore es solo estetica y nunca se parsea.
 */
public final class Keys {

    // --- Libros de encantamiento ---
    public static NamespacedKey ENCHANT_ID;
    public static NamespacedKey ENCHANT_LEVEL;
    public static NamespacedKey SUCCESS;
    public static NamespacedKey DESTROY;
    public static NamespacedKey TIER;

    // --- Polvos magicos ---
    /** Identificador del polvo (STRING). */
    public static NamespacedKey DUST_ID;
    /** Modo del polvo: destroy = baja ruptura · success = sube exito (STRING). */
    public static NamespacedKey DUST_MODE;
    /** Puntos porcentuales que aplica el polvo (INTEGER). */
    public static NamespacedKey DUST_VALUE;

    private static Plugin plugin;

    private Keys() {
    }

    public static void init(Plugin pl) {
        plugin = pl;
        ENCHANT_ID = new NamespacedKey(pl, "fe_id");
        ENCHANT_LEVEL = new NamespacedKey(pl, "fe_level");
        SUCCESS = new NamespacedKey(pl, "fe_success");
        DESTROY = new NamespacedKey(pl, "fe_destroy");
        TIER = new NamespacedKey(pl, "fe_tier");
        DUST_ID = new NamespacedKey(pl, "fd_id");
        DUST_MODE = new NamespacedKey(pl, "fd_mode");
        DUST_VALUE = new NamespacedKey(pl, "fd_value");
    }

    /** Clave por-encantamiento en el item destino: ench_&lt;id&gt; -&gt; nivel (INTEGER). */
    public static NamespacedKey enchantOnItem(String enchantId) {
        return new NamespacedKey(plugin, "ench_" + enchantId);
    }
}
