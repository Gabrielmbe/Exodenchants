package dev.fce;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carga y renderiza los menus de guis/*.yml (fill -> border -> accents -> items)
 * y ejecuta sus acciones.
 *
 * Las GUIs con menu.dynamic: enchant-catalog se rellenan solas con los
 * encantamientos de enchants/ *.yml, ordenados por tier y paginados. La lista
 * puede filtrarse por grupo de items (Categorias) o por material concreto
 * (inspector de admin).
 */
public class MenuManager implements Listener {

    private static final Pattern BUY_PATTERN =
            Pattern.compile("tier:\\s*(\\w+).*?price:\\s*(\\d+(?:\\.\\d+)?)");
    private static final Pattern MIN_SCORE_PATTERN = Pattern.compile("min-score:\\s*(\\d+)");

    private final FabledCustomEnchantsPlugin plugin;
    private final Map<String, YamlConfiguration> menus = new HashMap<>();

    public MenuManager(FabledCustomEnchantsPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        menus.clear();
        File dir = new File(plugin.getDataFolder(), "guis");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            menus.put(yml.getString("menu.id", file.getName().replace(".yml", "")), yml);
        }
        plugin.getLogger().info("Menus cargados: " + menus.size());
    }

    public void open(Player player, String menuId) {
        openView(player, menuId, null, null, 0);
    }

    /** Catalogo filtrado por grupo de items (Espadas, Picos, Hachas...). */
    public void openGroup(Player player, String group) {
        openView(player, "catalog", group, null, 0);
    }

    /** Inspector: solo los encantos compatibles con ese material. */
    public void openForMaterial(Player player, Material material) {
        openView(player, "admin_item_enchants", null, material, 0);
    }

    private void openView(Player player, String menuId, String group, Material material, int page) {
        YamlConfiguration yml = menus.get(menuId);
        if (yml == null) return;

        MenuHolder holder = new MenuHolder(menuId, group, material);
        List<EnchantDefinition> list = resolveList(holder);
        List<Integer> entrySlots = yml.getIntegerList("catalog.slots");
        int perPage = Math.max(1, entrySlots.size());
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) perPage));
        holder.page = Math.max(0, Math.min(page, pages - 1));

        int rows = Math.max(1, Math.min(6, yml.getInt("menu.rows", 6)));
        Inventory inv = Bukkit.createInventory(holder, rows * 9,
                BookFactory.line(yml.getString("menu.title", menuId)));
        holder.inventory = inv;

        paintLayout(inv, yml);
        placeItems(player, inv, holder, yml, list.size(), pages);
        String dynamic = yml.getString("menu.dynamic", "");
        if ("enchant-catalog".equalsIgnoreCase(dynamic)) {
            fillCatalog(player, inv, holder, yml, list, entrySlots, perPage);
        } else if ("black-market".equalsIgnoreCase(dynamic)) {
            fillMarket(inv, holder, yml);
        }

        String openSound = yml.getString("menu.open-sound");
        if (openSound != null) plugin.messages().playSound(player, openSound);
        player.openInventory(inv);
    }

    /** Lista base del catalogo, siempre ordenada por tier (Comun -> Legendario). */
    private List<EnchantDefinition> resolveList(MenuHolder holder) {
        List<EnchantDefinition> source;
        if (holder.material != null) {
            source = plugin.enchants().byMaterial(holder.material);
        } else if (holder.group != null) {
            source = plugin.enchants().byGroup(holder.group);
        } else {
            source = new ArrayList<>(plugin.enchants().all());
        }
        List<EnchantDefinition> ordered = new ArrayList<>();
        for (TierRegistry.Tier tier : plugin.tiers().all()) {
            for (EnchantDefinition def : source) {
                if (def.tierId().equalsIgnoreCase(tier.id())) ordered.add(def);
            }
        }
        for (EnchantDefinition def : source) {
            if (!ordered.contains(def)) ordered.add(def); // tier desconocido: al final
        }
        return ordered;
    }

    private void paintLayout(Inventory inv, YamlConfiguration yml) {
        ItemStack fill = pane(yml.getString("layout.fill.material"));
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, fill);

        ItemStack border = pane(yml.getString("layout.border.material"));
        for (int slot : yml.getIntegerList("layout.border.slots")) {
            if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, border);
        }
        ItemStack accent = pane(yml.getString("layout.accents.material"));
        for (int slot : yml.getIntegerList("layout.accents.slots")) {
            if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, accent);
        }
    }

    private void placeItems(Player player, Inventory inv, MenuHolder holder,
                            YamlConfiguration yml, int listSize, int pages) {
        ConfigurationSection items = yml.getConfigurationSection("items");
        if (items == null) return;
        for (String key : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(key);
            if (item == null) continue;
            int slot = item.getInt("slot", -1);
            if (slot < 0 || slot >= inv.getSize()) continue;

            Material material = Material.matchMaterial(item.getString("material", "STONE"));
            if (material == null) material = Material.STONE;
            ItemStack stack = new ItemStack(material);
            ItemMeta meta = stack.getItemMeta();
            meta.displayName(BookFactory.line(
                    menuPlaceholders(item.getString("name", " "), player, holder, listSize, pages)));
            List<Component> lore = new ArrayList<>();
            for (String rawLine : item.getStringList("lore")) {
                lore.add(BookFactory.line(menuPlaceholders(rawLine, player, holder, listSize, pages)));
            }
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            stack.setItemMeta(meta);
            inv.setItem(slot, stack);
            holder.actions.put(slot, item.getStringList("actions"));
        }
    }

    /** Placeholders de los items estaticos de cualquier GUI. */
    private String menuPlaceholders(String raw, Player player, MenuHolder holder,
                                    int listSize, int pages) {
        String result = raw
                .replace("{balance}", money(plugin.vault().balance(player)))
                .replace("{total}", String.valueOf(plugin.enchants().all().size()))
                .replace("{pagina}", String.valueOf(holder.page + 1))
                .replace("{paginas}", String.valueOf(pages))
                .replace("{mostrados}", String.valueOf(listSize))
                .replace("{polvos}", String.valueOf(plugin.dusts().all().size()))
                .replace("{ruptura_minima}", String.valueOf(
                        plugin.getConfig().getInt("limits.min-destroy-rate", 0)))
                .replace("{exito_maximo}", String.valueOf(
                        Math.min(100, plugin.getConfig().getInt("limits.max-success-rate", 100))))
                .replace("{item}", holder.material == null ? "—" : prettyMaterial(holder.material))
                .replace("{mercado_tiempo}", plugin.market().timeLeft())
                .replace("{mercado_ofertas}", String.valueOf(plugin.market().offers().size()))
                .replace("{combo}", comboName(player))
                .replace("{rank}", rankLabel(player))
                .replace("{score}", String.valueOf(plugin.stats().score(player.getUniqueId())));

        // Ranking: top 1-10 para las GUIs decorativas
        if (result.contains("{top_")) {
            java.util.List<PlayerStats.Entry> top = plugin.stats().top(10);
            for (int i = 1; i <= 10; i++) {
                String name = i <= top.size() ? top.get(i - 1).name() : "—";
                String score = i <= top.size() ? String.valueOf(top.get(i - 1).score()) : "0";
                result = result
                        .replace("{top_name_" + i + "}", name)
                        .replace("{top_score_" + i + "}", score);
            }
        }
        for (TierRegistry.Tier tier : plugin.tiers().all()) {
            result = result.replace("{pool_" + tier.id() + "}",
                    String.valueOf(plugin.enchants().byTier(tier.id()).size()));
        }
        for (String group : plugin.enchants().groupNames()) {
            result = result.replace("{grupo_" + group + "}",
                    String.valueOf(plugin.enchants().countByGroup(group)));
        }
        // Precios y reducciones reales de dusts/*.yml: la GUI nunca se desincroniza
        double cheapest = Double.MAX_VALUE;
        for (DustRegistry.Dust dust : plugin.dusts().all()) {
            result = result
                    .replace("{dust_price_" + dust.id() + "}", money(dust.price()))
                    .replace("{dust_value_" + dust.id() + "}", String.valueOf(dust.value()))
                    .replace("{dust_sign_" + dust.id() + "}", dust.boostsSuccess() ? "+" : "-")
                    // Alias historicos
                    .replace("{dust_reduce_" + dust.id() + "}", String.valueOf(dust.value()))
                    .replace("{dust_boost_" + dust.id() + "}", String.valueOf(dust.value()));
            cheapest = Math.min(cheapest, dust.price());
        }
        result = result.replace("{polvo_precio_min}",
                cheapest == Double.MAX_VALUE ? "—" : money(cheapest));
        return result;
    }

    private static String money(double value) {
        return String.format(Locale.US, "%,.0f", value);
    }

    /**
     * Entradas autogeneradas del catalogo (paginadas). Si la GUI declara
     * catalog.admin-give y el jugador tiene fce.admin, cada entrada entrega
     * su libro al hacer click.
     */
    private void fillCatalog(Player player, Inventory inv, MenuHolder holder, YamlConfiguration yml,
                             List<EnchantDefinition> list, List<Integer> slots, int perPage) {
        String nameTemplate = yml.getString("catalog.entry.name", "{enchant}");
        List<String> loreTemplate = yml.getStringList("catalog.entry.lore");
        ConfigurationSection groupNames = yml.getConfigurationSection("catalog.group-names");
        boolean adminGive = yml.getBoolean("catalog.admin-give", false)
                && player.hasPermission("fce.admin");

        int from = holder.page * perPage;
        for (int i = 0; i < perPage && from + i < list.size(); i++) {
            EnchantDefinition def = list.get(from + i);
            int slot = slots.get(i);
            if (slot < 0 || slot >= inv.getSize()) continue;
            TierRegistry.Tier tier = plugin.tiers().get(def.tierId());
            if (tier == null) continue;

            Material icon = Material.matchMaterial(def.icon());
            if (icon == null) icon = Material.ENCHANTED_BOOK;
            ItemStack stack = new ItemStack(icon);
            ItemMeta meta = stack.getItemMeta();
            meta.displayName(BookFactory.line(
                    catalogPlaceholders(nameTemplate, def, tier, groupNames)));

            List<Component> lore = new ArrayList<>();
            for (String raw : loreTemplate) {
                if (raw.contains("{descripcion}")) {
                    for (String desc : def.description()) lore.add(BookFactory.line(desc));
                } else {
                    lore.add(BookFactory.line(catalogPlaceholders(raw, def, tier, groupNames)));
                }
            }
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            stack.setItemMeta(meta);
            inv.setItem(slot, stack);

            if (adminGive) {
                holder.actions.put(slot, List.of("give-book: " + def.id()));
            }
        }
    }

    private String catalogPlaceholders(String raw, EnchantDefinition def,
                                       TierRegistry.Tier tier, ConfigurationSection groupNames) {
        List<String> pretty = new ArrayList<>();
        for (String group : def.applicableGroups()) {
            pretty.add(groupNames != null ? groupNames.getString(group, group) : group);
        }
        // Orden importante: los placeholders largos van antes que sus prefijos
        return raw
                .replace("{enchant}", def.displayName())
                .replace("{tier_color}", tier.color())
                .replace("{tier}", tier.display())
                .replace("{max_nivel_romano}", BookFactory.roman(def.maxLevel()))
                .replace("{max_nivel}", String.valueOf(def.maxLevel()))
                .replace("{aplica}", String.join(", ", pretty));
    }

    /**
     * Ofertas del MERCADO NEGRO en sus ranuras. El icono es el propio libro o
     * polvo generado, para que el jugador vea sus numeros reales antes de pagar.
     */
    private void fillMarket(Inventory inv, MenuHolder holder, YamlConfiguration yml) {
        if (!plugin.market().enabled()) return;
        List<Integer> slots = yml.getIntegerList("market.slots");
        if (slots.isEmpty()) slots = plugin.market().slots();
        List<String> loreTemplate = yml.getStringList("market.entry.lore");
        List<BlackMarketManager.Offer> offers = plugin.market().offers();

        for (int i = 0; i < offers.size() && i < slots.size(); i++) {
            BlackMarketManager.Offer offer = offers.get(i);
            int slot = slots.get(i);
            if (slot < 0 || slot >= inv.getSize()) continue;

            ItemStack icon = plugin.market().icon(offer);
            ItemMeta meta = icon.getItemMeta();
            List<Component> lore = new ArrayList<>();
            for (String raw : loreTemplate) {
                lore.add(BookFactory.line(raw
                        .replace("{articulo}", plugin.market().displayName(offer))
                        .replace("{tier}", plugin.market().tierDisplay(offer))
                        .replace("{descuento}", String.valueOf(offer.discount()))
                        .replace("{precio}", money(offer.price()))
                        .replace("{exito}", String.valueOf(offer.success()))
                        .replace("{ruptura}", String.valueOf(offer.destroy()))
                        .replace("{nivel_romano}", BookFactory.roman(offer.level()))
                        .replace("{mercado_tiempo}", plugin.market().timeLeft())));
            }
            meta.lore(lore);
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
            holder.actions.put(slot, List.of("buy-offer: " + i));
        }
    }

    private String comboName(Player player) {
        SetComboManager.Combo combo = plugin.combos().activeCombo(player);
        return combo == null ? "<gray>ninguno" : combo.display();
    }

    private String rankLabel(Player player) {
        int rank = plugin.stats().rankOf(player.getUniqueId());
        return rank == 0 ? "—" : "#" + rank;
    }

    private int parseIndex(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    static String prettyMaterial(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    private ItemStack pane(String materialName) {
        Material material = materialName == null ? null : Material.matchMaterial(materialName);
        if (material == null) material = Material.GRAY_STAINED_GLASS_PANE;
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(" "));
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        List<String> actions = holder.actions.get(event.getRawSlot());
        if (actions == null) return;
        for (String action : actions) {
            if (!runAction(player, holder, action)) break; // accion fallida corta la cadena
        }
    }

    private boolean runAction(Player player, MenuHolder holder, String action) {
        if (action.equalsIgnoreCase("close")) {
            player.closeInventory();
            return true;
        }
        if (action.equalsIgnoreCase("toggle-inspect")) {
            plugin.inspector().toggle(player);
            player.closeInventory();
            return true;
        }
        int idx = action.indexOf(':');
        if (idx < 0) return true;
        String type = action.substring(0, idx).trim().toLowerCase(Locale.ROOT);
        String value = action.substring(idx + 1).trim();

        return switch (type) {
            case "sound" -> {
                plugin.messages().playSound(player, value);
                yield true;
            }
            case "open-gui" -> {
                open(player, value);
                yield true;
            }
            case "open-filter" -> {
                openGroup(player, value.toUpperCase(Locale.ROOT));
                yield true;
            }
            case "page" -> {
                int delta = value.equalsIgnoreCase("prev") ? -1 : 1;
                openView(player, holder.menuId, holder.group, holder.material, holder.page + delta);
                yield true;
            }
            case "buy-book" -> buyBook(player, value);
            case "buy-offer" -> {
                int index = parseIndex(value);
                boolean bought = index >= 0 && plugin.market().buy(player, index);
                if (bought) openView(player, holder.menuId, null, null, 0); // refresca el catalogo
                yield bought;
            }
            case "buy-dust" -> buyDust(player, value);
            case "give-book" -> giveBook(player, value);
            default -> true;
        };
    }

    private boolean buyBook(Player player, String value) {
        Matcher matcher = BUY_PATTERN.matcher(value);
        if (!matcher.find()) return false;
        TierRegistry.Tier tier = plugin.tiers().get(matcher.group(1));
        if (tier == null) return false;
        double price = Double.parseDouble(matcher.group(2));

        // REQUISITO DE RANGO: los tiers altos exigen puntos de encantador
        // (config.yml -> ranking.points). Se declara en la accion buy-book
        // con min-score, asi cada GUI decide sus propios umbrales.
        Matcher requirement = MIN_SCORE_PATTERN.matcher(value);
        if (requirement.find()) {
            int needed = Integer.parseInt(requirement.group(1));
            int have = plugin.stats().score(player.getUniqueId());
            if (have < needed) {
                plugin.messages().playSound(player, "purchase-denied");
                plugin.messages().send(player, "shop-locked",
                        "puntos", String.valueOf(needed),
                        "actual", String.valueOf(have));
                return false;
            }
        }

        // Pool del tier ANTES de cobrar: si esta vacio, avisa y no cobra
        List<EnchantDefinition> pool = plugin.enchants().byTier(tier.id());
        if (pool.isEmpty()) {
            plugin.messages().playSound(player, "purchase-denied");
            plugin.messages().send(player, "shop-empty");
            return false;
        }
        if (!plugin.vault().withdraw(player, price)) {
            denyPurchase(player, price);
            return false;
        }

        EnchantDefinition def = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        deliver(player, def, tier);
        plugin.messages().playSound(player, "purchase-success");
        plugin.announcer().purchased(player, def, tier, price);
        return true;
    }

    /**
     * Compra de polvo magico. El precio sale de dusts/&lt;id&gt;.yml, no de la
     * GUI, para que no puedan divergir.
     */
    private boolean buyDust(Player player, String dustId) {
        DustRegistry.Dust dust = plugin.dusts().get(dustId);
        if (dust == null) return false;

        if (!plugin.vault().withdraw(player, dust.price())) {
            denyPurchase(player, dust.price());
            return false;
        }

        give(player, plugin.dusts().create(dust, 1));
        plugin.messages().playSound(player, "purchase-success");
        plugin.messages().send(player, "dust-purchased",
                "polvo", dust.displayName(),
                "reduccion", String.valueOf(dust.value()));
        return true;
    }

    /** Entrega directa desde el inspector (solo admins). */
    private boolean giveBook(Player player, String enchantId) {
        if (!player.hasPermission("fce.admin")) {
            plugin.messages().send(player, "no-permission");
            return false;
        }
        EnchantDefinition def = plugin.enchants().get(enchantId);
        if (def == null) return false;
        TierRegistry.Tier tier = plugin.tiers().get(def.tierId());
        if (tier == null) return false;

        int[] data = deliver(player, def, tier);
        plugin.messages().playSound(player, "purchase-success");
        plugin.messages().send(player, "admin-book-given",
                "enchant", def.displayName(),
                "nivel_romano", BookFactory.roman(data[0]),
                "exito", String.valueOf(data[1]),
                "ruptura", String.valueOf(data[2]));
        return true;
    }

    private void denyPurchase(Player player, double price) {
        plugin.messages().playSound(player, "purchase-denied");
        plugin.messages().send(player, "no-money", "precio", money(price));
    }

    /** Genera y entrega el libro. Devuelve {nivel, exito, ruptura}. */
    private int[] deliver(Player player, EnchantDefinition def, TierRegistry.Tier tier) {
        int maxLevel = Math.max(1, Math.min(tier.maxBookLevel(), def.maxLevel()));
        int level = ThreadLocalRandom.current().nextInt(1, maxLevel + 1);
        int success = plugin.tiers().rollSuccess(tier);
        int destroy = plugin.tiers().rollDestroy(tier, success);

        give(player, plugin.books().create(def, tier, level, success, destroy));
        return new int[]{level, success, destroy};
    }

    /** Al inventario, y lo que no quepa al suelo. */
    private void give(Player player, ItemStack stack) {
        player.getInventory().addItem(stack).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
    }

    /** Holder propio: identifica los menus del plugin y guarda pagina + filtro. */
    static final class MenuHolder implements InventoryHolder {
        final String menuId;
        final String group;
        final Material material;
        final Map<Integer, List<String>> actions = new HashMap<>();
        int page;
        Inventory inventory;

        MenuHolder(String menuId, String group, Material material) {
            this.menuId = menuId;
            this.group = group;
            this.material = material;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
