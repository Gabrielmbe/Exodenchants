package dev.fce;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Carga y consulta los encantamientos (enchants/*.yml) y los grupos de items
 * aplicables (modules/drag_and_drop.yml -> item-groups).
 */
public class EnchantRegistry {

    private final Map<String, EnchantDefinition> byId = new LinkedHashMap<>();
    private final Map<String, Set<Material>> groups = new LinkedHashMap<>();

    public void load(JavaPlugin plugin) {
        byId.clear();
        groups.clear();

        YamlConfiguration module = YamlConfiguration.loadConfiguration(
                new File(plugin.getDataFolder(), "modules/drag_and_drop.yml"));
        ConfigurationSection groupSec = module.getConfigurationSection("item-groups");
        if (groupSec != null) {
            for (String group : groupSec.getKeys(false)) {
                Set<Material> mats = EnumSet.noneOf(Material.class);
                for (String name : groupSec.getStringList(group)) {
                    Material mat = Material.matchMaterial(name);
                    if (mat != null) mats.add(mat);
                }
                groups.put(group.toUpperCase(Locale.ROOT), mats);
            }
        }

        File dir = new File(plugin.getDataFolder(), "enchants");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            if (!yml.getBoolean("enabled", true)) continue;
            String id = yml.getString("id");
            if (id == null || id.isBlank()) continue;
            id = id.toLowerCase(Locale.ROOT);
            byId.put(id, new EnchantDefinition(
                    id,
                    yml.getString("display-name", id),
                    yml.getString("tier", "comun"),
                    yml.getInt("max-level", 1),
                    yml.getStringList("applicable-to"),
                    yml.getString("lore-line", "<gray>" + id + " {nivel_romano}"),
                    yml.getStringList("description"),
                    yml.getString("fabled-skill", ""),
                    yml.getString("icon", "ENCHANTED_BOOK"),
                    readEffects(yml.getConfigurationSection("effects")),
                    readMechanic(yml.getConfigurationSection("mechanic"))));
        }

        int withEffects = 0, withMechanic = 0;
        for (EnchantDefinition def : byId.values()) {
            if (def.hasEffects()) withEffects++;
            if (def.hasMechanic()) withMechanic++;
        }
        plugin.getLogger().info("Encantamientos cargados: " + byId.size()
                + " (efectos nativos: " + withEffects + " · mecanicas: " + withMechanic + ")");
    }

    private EnchantDefinition.Effects readEffects(ConfigurationSection sec) {
        if (sec == null) return null;
        List<EnchantDefinition.Action> actions = new ArrayList<>();
        for (Map<?, ?> raw : sec.getMapList("actions")) {
            String type = str(raw, "type", "");
            if (type.isBlank()) continue;
            actions.add(new EnchantDefinition.Action(
                    type.toLowerCase(Locale.ROOT),
                    str(raw, "target", "self").toLowerCase(Locale.ROOT),
                    str(raw, "value", ""),
                    (int) num(raw, "amplifier", 0),
                    num(raw, "value-base", 0),
                    num(raw, "value-scale", 0),
                    num(raw, "seconds-base", 0),
                    num(raw, "seconds-scale", 0),
                    Boolean.parseBoolean(str(raw, "true-damage", "false")),
                    num(raw, "volume", 0.8),
                    num(raw, "pitch", 1.0),
                    (int) num(raw, "count", 12)));
        }
        if (actions.isEmpty()) return null;
        return new EnchantDefinition.Effects(
                sec.getString("trigger", "attack").toLowerCase(Locale.ROOT),
                sec.getDouble("chance-base", 100),
                sec.getDouble("chance-scale", 0),
                actions);
    }

    private EnchantDefinition.Mechanic readMechanic(ConfigurationSection sec) {
        if (sec == null) return null;
        String type = sec.getString("type", "");
        if (type.isBlank()) return null;
        return new EnchantDefinition.Mechanic(
                type.toLowerCase(Locale.ROOT),
                sec.getDouble("value-base", 0),
                sec.getDouble("value-scale", 0),
                sec.getDouble("chance-base", 100),
                sec.getDouble("chance-scale", 0));
    }

    private static String str(Map<?, ?> map, String key, String def) {
        Object value = map.get(key);
        return value == null ? def : String.valueOf(value);
    }

    private static double num(Map<?, ?> map, String key, double def) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return def;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    public EnchantDefinition get(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<EnchantDefinition> all() {
        return byId.values();
    }

    public Set<String> groupNames() {
        return groups.keySet();
    }

    public List<EnchantDefinition> byTier(String tierId) {
        return byId.values().stream()
                .filter(def -> def.tierId().equalsIgnoreCase(tierId))
                .toList();
    }

    public List<EnchantDefinition> byGroup(String group) {
        List<EnchantDefinition> result = new ArrayList<>();
        for (EnchantDefinition def : byId.values()) {
            for (String g : def.applicableGroups()) {
                if (g.equalsIgnoreCase(group)) {
                    result.add(def);
                    break;
                }
            }
        }
        return result;
    }

    public List<EnchantDefinition> byMaterial(Material material) {
        List<EnchantDefinition> result = new ArrayList<>();
        for (EnchantDefinition def : byId.values()) {
            if (isApplicable(def, material)) result.add(def);
        }
        return result;
    }

    public int countByGroup(String group) {
        return byGroup(group).size();
    }

    public boolean isApplicable(EnchantDefinition def, Material material) {
        for (String group : def.applicableGroups()) {
            Set<Material> mats = groups.get(group.toUpperCase(Locale.ROOT));
            if (mats != null && mats.contains(material)) return true;
        }
        return false;
    }

    public List<String> groupsOf(Material material) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Set<Material>> entry : groups.entrySet()) {
            if (entry.getValue().contains(material)) result.add(entry.getKey());
        }
        return result;
    }

    /** Nivel de un encantamiento en un item concreto (0 = no lo tiene). */
    public int levelOn(ItemStack item, EnchantDefinition def) {
        if (item == null || !item.hasItemMeta()) return 0;
        return item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(Keys.enchantOnItem(def.id()), PersistentDataType.INTEGER, 0);
    }

    /** Todos los encantamientos presentes en un item, con su nivel. */
    public Map<EnchantDefinition, Integer> onItem(ItemStack item) {
        Map<EnchantDefinition, Integer> found = new LinkedHashMap<>();
        if (item == null || !item.hasItemMeta()) return found;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        for (EnchantDefinition def : byId.values()) {
            int level = pdc.getOrDefault(Keys.enchantOnItem(def.id()), PersistentDataType.INTEGER, 0);
            if (level > 0) found.put(def, level);
        }
        return found;
    }
}
