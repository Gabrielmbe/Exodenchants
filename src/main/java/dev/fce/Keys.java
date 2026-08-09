package dev.fce;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * Claves del PersistentDataContainer (minecraft:custom_data).
 * Unica fuente de verdad de los datos de libros, polvos e items:
 * el Lore es solo estetica y nunca se parsea.
 *
 * IMPORTANTE: el namespace ahora es FIJO ("fabledcustomenchants") y ya
 * NO depende del nombre del plugin. Asi, aunque el plugin se renombre
 * (ExodeEnchant), todos los items generados antes del cambio —libros,
 * polvos y equipo ya encantado— siguen siendo reconocidos.
 *
 * NO cambiar NAMESPACE: hacerlo invalida todos los items existentes
 * del servidor.
 */
public final class Keys {

    /** Namespace fijo de todas las claves del sistema. */
    public static final String NAMESPACE = "fabledcustomenchants";

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

    private Keys() {
    }

    /** El parametro se conserva por compatibilidad con onEnable(). */
    public static void init(Plugin pl) {
        ENCHANT_ID = of("fe_id");
        ENCHANT_LEVEL = of("fe_level");
        SUCCESS = of("fe_success");
        DESTROY = of("fe_destroy");
        TIER = of("fe_tier");
        DUST_ID = of("fd_id");
        DUST_MODE = of("fd_mode");
        DUST_VALUE = of("fd_value");
    }

    /** Crea una clave en el namespace fijo del sistema. */
    public static NamespacedKey of(String key) {
        return Objects.requireNonNull(
                NamespacedKey.fromString(NAMESPACE + ":" + key),
                "Clave invalida: " + key);
    }

    /** Clave por-encantamiento en el item destino: ench_<id> -> nivel (INTEGER). */
    public static NamespacedKey enchantOnItem(String enchantId) {
        return of("ench_" + enchantId);
    }
}
