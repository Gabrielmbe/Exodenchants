package dev.fce;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;

/**
 * FabledCustomEnchants — puente de encantamientos personalizados para Fabled 5.
 * Paper 1.21+ / 26.x · Java 21+
 */
public final class FabledCustomEnchantsPlugin extends JavaPlugin {

    private static final String[] ENCHANT_FILES = {
            // Comun
            "llamarada", "vigor", "paso_ligero", "desgarro", "cegar", "veta", "topo",
            "caparazon", "aguante", "amortiguar", "flecha_helada", "marchitar", "vertigo",
            // Raro
            "vampirismo", "congelacion", "toxina", "aturdir", "sangrado", "furia",
            "desarme", "fortuna_arcana", "contragolpe", "tiro_certero", "gravedad", "quebranto",
            // Legendario
            "zeus", "colosal", "verdugo", "sismo", "barrena", "bastion", "titan", "pulso_vital",
            // Mitico
            "carniceria", "absolucion", "tormenta", "avaricia", "berserker", "inmortal",
            "sombra", "ejecucion", "vendaval", "cazador", "hambruna",
            // Divino
            "apocalipsis", "juicio_final", "renacer", "egida", "voragine", "alma_negra",
            "corazon_estelar", "minero_divino", "furia_titanica", "arco_celestial",
            // Mecanicas nativas · picos
            "vetadora", "perforadora", "fundicion_arcana", "telequinesis", "prosperidad",
            "sabiduria", "zahori", "autoforja", "detonador",
            // Mecanicas nativas · hachas
            "talador", "reforestador", "aserradero", "resinador", "descortezador",
            "podadora", "recolector_arboreo", "furia_lenador"
    };

    private static final String[] DUST_FILES = {
            // mode: destroy -> bajan la ruptura
            "polvo_menor", "polvo_arcano", "polvo_celestial", "polvo_estelar", "polvo_primordial",
            // mode: success -> suben el exito
            "esencia_menor", "esencia_mayor", "esencia_divina"
    };

    private static final String[] GUI_FILES = {
            "main_menu", "enchanter_shop", "dust_shop", "catalog", "categories",
            "admin_item_enchants", "black_market", "ranking"
    };

    private final EnchantRegistry enchants = new EnchantRegistry();
    private final TierRegistry tiers = new TierRegistry();
    private final DustRegistry dusts = new DustRegistry();
    private final BookFactory books = new BookFactory();
    private final VaultHook vault = new VaultHook();
    private Messages messages;
    private MenuManager menus;
    private FabledBridge bridge;
    private EffectEngine effects;
    private ToolMechanicsListener toolMechanics;
    private PlayerStats stats;
    private AnnounceService announcer;
    private BlackMarketManager market;
    private SetComboManager combos;
    private AdminInspectListener inspector;
    private FusionListener fusion;
    private RecycleListener recycler;
    private TraderManager trader;
    private dev.fce.security.AntiDupeListener antiDupe;

    @Override
    public void onEnable() {
        saveDefaults();
        Keys.init(this);

        messages = new Messages(this);
        enchants.load(this);
        tiers.load(this);
        dusts.load(this);
        books.load(this);
        vault.setup();

        stats = new PlayerStats(this);
        stats.load();
        stats.startAutosave();
        announcer = new AnnounceService(this);
        market = new BlackMarketManager(this);
        market.load();
        combos = new SetComboManager(this);
        combos.load();

        menus = new MenuManager(this);
        menus.load();
        bridge = new FabledBridge(this);
        effects = new EffectEngine(this);
        toolMechanics = new ToolMechanicsListener(this);
        inspector = new AdminInspectListener(this);
        fusion = new FusionListener(this);
        fusion.load();
        recycler = new RecycleListener(this);
        recycler.load();
        trader = new TraderManager(this);
        trader.load();

        getServer().getPluginManager().registerEvents(new DragAndDropListener(this), this);
        // Racha de suerte visible: pinta el bono de pity en el lore del libro
        // que el jugador sostiene con el cursor, antes de aplicarlo.
        getServer().getPluginManager().registerEvents(new LuckLoreListener(this), this);
        // Forja de Fusion: libro sobre libro identico -> nivel superior.
        // Corre en prioridad NORMAL (tras el antidupe, antes del drag & drop).
        getServer().getPluginManager().registerEvents(fusion, this);
        // Trituradora: libro sobre piedra de afilar -> polvo/esencias.
        getServer().getPluginManager().registerEvents(recycler, this);
        getServer().getPluginManager().registerEvents(menus, this);
        getServer().getPluginManager().registerEvents(bridge, this);
        getServer().getPluginManager().registerEvents(inspector, this);
        // Motores propios: los efectos se ejecutan aunque Fabled no este presente
        getServer().getPluginManager().registerEvents(effects, this);
        getServer().getPluginManager().registerEvents(toolMechanics, this);
        getServer().getPluginManager().registerEvents(combos, this);
        // Encantador Errante: proteccion del NPC de trades
        getServer().getPluginManager().registerEvents(trader, this);
        antiDupe = dev.fce.security.AntiDupeListener.register(this);
        combos.start();
        hookPlaceholders();

        // El motor puede habilitarse despues que este plugin: se verifica al
        // terminar la carga del servidor, no aqui.
        bridge.verifyEngineLater();
        bridge.startPeriodicSync();

        getLogger().info("FabledCustomEnchants habilitado ("
                + enchants.all().size() + " encantamientos, "
                + tiers.all().size() + " tiers, "
                + dusts.all().size() + " polvos).");
    }

    @Override
    public void onDisable() {
        if (stats != null) stats.saveNow();
    }

    /** Registra la expansion de PlaceholderAPI solo si el plugin esta presente. */
    private void hookPlaceholders() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) return;
        try {
            new PlaceholderHook(this).register();
            getLogger().info("PlaceholderAPI detectado: placeholders %fce_...% disponibles.");
        } catch (Throwable t) {
            getLogger().warning("No se pudo registrar la expansion de PlaceholderAPI: " + t.getMessage());
        }
    }

    private void saveDefaults() {
        saveIfAbsent("config.yml");
        saveIfAbsent("modules/drag_and_drop.yml");
        saveIfAbsent("modules/set_combos.yml");
        saveIfAbsent("modules/black_market.yml");
        saveIfAbsent("modules/fusion.yml");
        saveIfAbsent("modules/recycle.yml");
        saveIfAbsent("modules/trader.yml");
        saveIfAbsent("pools/tiers.yml");
        saveIfAbsent("books/libro_encantamiento.yml");
        for (String id : ENCHANT_FILES) saveIfAbsent("enchants/" + id + ".yml");
        for (String id : DUST_FILES) saveIfAbsent("dusts/" + id + ".yml");
        for (String id : GUI_FILES) saveIfAbsent("guis/" + id + ".yml");
    }

    private void saveIfAbsent(String resource) {
        if (!new File(getDataFolder(), resource).exists()) {
            saveResource(resource, false);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Solo disponible en el juego.");
            return true;
        }

        if (args.length == 0) {
            menus.open(player, "main_menu");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload" -> {
                if (!player.hasPermission("fce.admin")) {
                    messages.send(player, "no-permission");
                    return true;
                }
                reloadConfig();
                enchants.load(this);
                tiers.load(this);
                dusts.load(this);
                books.load(this);
                menus.load();
                market.load();
                combos.load();
                fusion.load();
                recycler.load();
                trader.load();
                stats.load();
                if (antiDupe != null) antiDupe.reload();
                player.sendMessage("FabledCustomEnchants: configuracion recargada ("
                        + enchants.all().size() + " encantamientos, "
                        + dusts.all().size() + " polvos).");
                return true;
            }
            case "debug" -> {
                if (!player.hasPermission("fce.admin")) {
                    messages.send(player, "no-permission");
                    return true;
                }
                for (String line : bridge.debugReport(player)) player.sendMessage(line);
                return true;
            }
            case "top", "ranking" -> {
                menus.open(player, "ranking");
                return true;
            }
            case "mercado", "market" -> {
                if (!market.enabled()) {
                    player.sendMessage("El mercado negro esta desactivado.");
                    return true;
                }
                menus.open(player, "black_market");
                return true;
            }
            case "combos" -> {
                messages.sendRaw(player, "<dark_gray>— <gradient:#F72585:#B5179E>Combos de set</gradient> <dark_gray>—");
                for (String line : combos.describe(player)) messages.sendRaw(player, line);
                return true;
            }
            case "fusion", "forja" -> {
                messages.sendRaw(player, "<dark_gray>— <gradient:#FFB703:#FB8500>Forja de Fusión</gradient> <dark_gray>—");
                for (String line : fusion.describe(player)) messages.sendRaw(player, line);
                return true;
            }
            case "reciclar", "recycle", "triturar" -> {
                messages.sendRaw(player, "<dark_gray>— <gradient:#8ECAE6:#219EBC>Trituradora</gradient> <dark_gray>—");
                for (String line : recycler.describe(player)) messages.sendRaw(player, line);
                return true;
            }
            case "inspect" -> {
                inspector.toggle(player);
                return true;
            }
            case "check" -> {
                if (!player.hasPermission("fce.admin")) {
                    messages.send(player, "no-permission");
                    return true;
                }
                inspector.inspect(player, player.getInventory().getItemInMainHand());
                return true;
            }
            case "categorias", "categories" -> {
                menus.open(player, "categories");
                return true;
            }
            case "catalogo", "catalog" -> {
                menus.open(player, "catalog");
                return true;
            }
            case "polvos", "dusts" -> {
                menus.open(player, "dust_shop");
                return true;
            }
            case "trader", "aldeano" -> {
                if (!player.hasPermission("fce.admin")) {
                    messages.send(player, "no-permission");
                    return true;
                }
                String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "spawn";
                switch (action) {
                    case "spawn" -> {
                        trader.spawn(player);
                        player.sendMessage("Encantador Errante invocado con stock aleatorio.");
                    }
                    case "remove", "quitar" -> {
                        int removed = trader.removeNearby(player, 6);
                        player.sendMessage(removed > 0
                                ? "Encantadores eliminados: " + removed
                                : "No hay ningun Encantador Errante cerca (6 bloques).");
                    }
                    case "refresh", "stock" -> {
                        boolean ok = trader.refreshNearby(player, 6);
                        player.sendMessage(ok
                                ? "Stock del Encantador renovado."
                                : "No hay ningun Encantador Errante cerca (6 bloques).");
                    }
                    default -> player.sendMessage("Uso: /" + label + " trader [spawn | remove | refresh]");
                }
                return true;
            }
            case "dust" -> {
                if (!player.hasPermission("fce.admin")) {
                    messages.send(player, "no-permission");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage("Uso: /" + label + " dust <polvo> [cantidad]");
                    return true;
                }
                DustRegistry.Dust dust = dusts.get(args[1]);
                if (dust == null) {
                    player.sendMessage("Polvo desconocido: " + args[1]);
                    return true;
                }
                int amount = 1;
                if (args.length >= 3) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
                    } catch (NumberFormatException ignored) {
                        amount = 1;
                    }
                }
                player.getInventory().addItem(dusts.create(dust, amount));
                messages.send(player, "dust-purchased",
                        "polvo", dust.displayName(),
                        "reduccion", String.valueOf(dust.value()));
                return true;
            }
            case "give" -> {
                if (!player.hasPermission("fce.admin")) {
                    messages.send(player, "no-permission");
                    return true;
                }
                if (args.length < 3) {
                    player.sendMessage("Uso: /" + label + " give <enchant> <nivel> [exito] [ruptura]");
                    return true;
                }
                EnchantDefinition def = enchants.get(args[1]);
                if (def == null) {
                    player.sendMessage("Encantamiento desconocido: " + args[1]);
                    return true;
                }
                TierRegistry.Tier tier = tiers.get(def.tierId());
                if (tier == null) {
                    player.sendMessage("El tier del encantamiento no existe: " + def.tierId());
                    return true;
                }
                int level;
                try {
                    level = Math.max(1, Math.min(def.maxLevel(), Integer.parseInt(args[2])));
                } catch (NumberFormatException ex) {
                    player.sendMessage("Nivel invalido: " + args[2]);
                    return true;
                }
                int success = args.length >= 4 ? clampPercent(args[3]) : tiers.rollSuccess(tier);
                int destroy = args.length >= 5 ? clampPercent(args[4]) : tiers.rollDestroy(tier, success);
                player.getInventory().addItem(books.create(def, tier, level, success, destroy));
                messages.send(player, "admin-book-given",
                        "enchant", def.displayName(),
                        "nivel_romano", BookFactory.roman(level),
                        "exito", String.valueOf(success),
                        "ruptura", String.valueOf(destroy));
                return true;
            }
            default -> {
                player.sendMessage("Uso: /" + label
                        + " [catalogo | categorias | polvos | mercado | top | combos | fusion | reciclar"
                        + " | inspect | check | debug | reload | trader <spawn|remove|refresh>"
                        + " | give <enchant> <nivel> [exito] [ruptura] | dust <polvo> [cantidad]]");
                return true;
            }
        }
    }

    private int clampPercent(String raw) {
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(raw)));
        } catch (NumberFormatException ex) {
            return 100;
        }
    }

    public EnchantRegistry enchants()       { return enchants; }
    public TierRegistry tiers()             { return tiers; }
    public DustRegistry dusts()             { return dusts; }
    public BookFactory books()              { return books; }
    public Messages messages()              { return messages; }
    public VaultHook vault()                { return vault; }
    public FabledBridge bridge()            { return bridge; }
    public MenuManager menus()              { return menus; }
    public AdminInspectListener inspector()  { return inspector; }
    public EffectEngine effects()           { return effects; }
    public PlayerStats stats()              { return stats; }
    public AnnounceService announcer()      { return announcer; }
    public BlackMarketManager market()      { return market; }
    public SetComboManager combos()         { return combos; }
    public ToolMechanicsListener tools()    { return toolMechanics; }
    public FusionListener fusion()          { return fusion; }
    public RecycleListener recycler()       { return recycler; }
    public TraderManager trader()           { return trader; }
}
