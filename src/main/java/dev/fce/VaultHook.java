package dev.fce;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Integracion opcional con Vault. Si no hay economia registrada,
 * las compras de la tienda quedan en modo prueba (gratis).
 */
public class VaultHook {

    private Economy economy;

    public void setup() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    public boolean available() {
        return economy != null;
    }

    public double balance(Player player) {
        return available() ? economy.getBalance(player) : 0;
    }

    /** true si el cobro se realizo (o no hay economia instalada). */
    public boolean withdraw(Player player, double amount) {
        if (!available()) return true;
        if (economy.getBalance(player) < amount) return false;
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }
}
