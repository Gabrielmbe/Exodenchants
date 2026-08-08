package dev.fce;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Mensajes (MiniMessage) y sonidos globales definidos en config.yml.
 */
public class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final JavaPlugin plugin;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** kv = pares placeholder/valor: send(p, "apply-success", "enchant", "Vampirismo"). */
    public void send(Player player, String key, String... kv) {
        String raw = plugin.getConfig().getString("messages." + key);
        if (raw == null || raw.isBlank()) return;
        player.sendMessage(MM.deserialize(fill(raw, kv)));
    }

    /** Envia una linea MiniMessage arbitraria (combos, mercado, anuncios). */
    public void sendRaw(Player player, String raw, String... kv) {
        if (raw == null || raw.isBlank()) return;
        player.sendMessage(MM.deserialize(fill(raw, kv)));
    }

    /** Anuncio a todo el servidor. */
    public void broadcast(String key, String... kv) {
        String raw = plugin.getConfig().getString("messages." + key);
        if (raw == null || raw.isBlank()) return;
        Component component = MM.deserialize(fill(raw, kv));
        Bukkit.getServer().sendMessage(component);
    }

    public Component render(String raw, String... kv) {
        return MM.deserialize(fill(raw, kv));
    }

    private String fill(String raw, String... kv) {
        String result = raw.replace("<prefix>", plugin.getConfig().getString("messages.prefix", ""));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            result = result.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return result;
    }

    /** Reproduce un sonido de config.yml -> sounds.<soundKey>. */
    public void playSound(Player player, String soundKey) {
        String base = "sounds." + soundKey + ".";
        String key = plugin.getConfig().getString(base + "key");
        if (key == null || key.isBlank()) return;
        player.playSound(player.getLocation(), key,
                (float) plugin.getConfig().getDouble(base + "volume", 1.0),
                (float) plugin.getConfig().getDouble(base + "pitch", 1.0));
    }

    /** Sonido para todo el servidor (anuncios de libros raros). */
    public void broadcastSound(String soundKey) {
        String base = "sounds." + soundKey + ".";
        String key = plugin.getConfig().getString(base + "key");
        if (key == null || key.isBlank()) return;
        float volume = (float) plugin.getConfig().getDouble(base + "volume", 1.0);
        float pitch = (float) plugin.getConfig().getDouble(base + "pitch", 1.0);
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.playSound(online.getLocation(), key, volume, pitch);
        }
    }
}
